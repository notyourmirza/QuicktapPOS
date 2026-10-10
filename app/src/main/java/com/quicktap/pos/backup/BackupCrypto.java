package com.quicktap.pos.backup;

import android.os.Build;

import androidx.annotation.Nullable;

import com.quicktap.pos.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The encrypted QuickTap backup file: how it is written and how it is read back.
 *
 * <p>Layout (all integers big-endian, AES-256/GCM with a 128-bit tag):</p>
 * <pre>
 *   "QTPB2"                      magic + format version
 *   byte   kdf                   0 = this device's Android Keystore key
 *                                1 = PBKDF2 over the user's passphrase
 *   [kdf==1] byte hash           0 = HmacSHA256, 1 = HmacSHA1 (older devices)
 *   [kdf==1] int   iterations
 *   [kdf==1] byte[16] salt
 *   int    ivLength, byte[] iv
 *   byte[] cipherText            plaintext below, encrypted
 *
 *   plaintext: "QTPB1" | UTF meta | int dbLength | db bytes
 * </pre>
 *
 * <p>A <em>device-key</em> backup is the strongest and the simplest: nobody else can
 * open it, but it can only ever be restored on the phone that made it (the key
 * lives in hardware and does not survive a reinstall). A <em>passphrase</em> backup
 * is what a shop needs when a till dies: the same file opens on any device.</p>
 *
 * <p>Files written before this format ("QTPB1" header, device key only) are still
 * readable, so no existing backup becomes unusable.</p>
 */
public final class BackupCrypto {

    public static final byte KDF_DEVICE_KEY = 0;
    public static final byte KDF_PASSPHRASE = 1;

    /** Passphrase stretching cost. 210 000 rounds is ~0.2 s on a mid-range till. */
    public static final int PBKDF2_ITERATIONS = 210_000;
    public static final int MIN_PASSPHRASE = 8;
    public static final int BACKUP_VERSION = 1;
    /** The Room schema this build writes; restore refuses a newer file. */
    public static final int SCHEMA_VERSION = 11;

    private static final String KEY_ALIAS = "quicktap_pos_backup_key_v1";
    private static final byte[] MAGIC = new byte[]{'Q', 'T', 'P', 'B', '1'};
    private static final byte[] MAGIC2 = new byte[]{'Q', 'T', 'P', 'B', '2'};
    private static final int SALT_LENGTH = 16;
    private static final int TAG_BITS = 128;
    private static final int MAX_DB_BYTES = 512 * 1024 * 1024;

    private BackupCrypto() { }

    // ------------------------------------------------------------------ write

    /**
     * Encrypts a database file into a cache file ready for upload.
     *
     * @param passphrase {@code null}/empty to seal the file with this device's key,
     *                   or a secret the shop can type on another phone later.
     */
    public static File encrypt(File outputFile, byte[] plainDatabase, @Nullable String passphrase)
            throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        JSONObject meta = new JSONObject()
                .put("backup_version", BACKUP_VERSION)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("schema_version", SCHEMA_VERSION)
                .put("created_at", System.currentTimeMillis())
                .put("database_size", plainDatabase.length)
                .put("sha256", sha256(plainDatabase));

        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(plain))) {
            out.write(MAGIC);
            out.writeUTF(meta.toString());
            out.writeInt(plainDatabase.length);
            out.write(plainDatabase);
        }

        boolean usesPassphrase = passphrase != null && !passphrase.isEmpty();
        byte[] salt = null;
        int hash = 0;
        SecretKey key;
        if (usesPassphrase) {
            salt = new byte[SALT_LENGTH];
            new SecureRandom().nextBytes(salt);
            hash = hashAlgorithm();
            key = passphraseKey(passphrase, hash, PBKDF2_ITERATIONS, salt);
        } else {
            key = deviceKey();
        }

        byte[] encrypted = aes(Cipher.ENCRYPT_MODE, key, iv, plain.toByteArray());

        try (DataOutputStream file = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile)))) {
            file.write(MAGIC2);
            file.writeByte(usesPassphrase ? KDF_PASSPHRASE : KDF_DEVICE_KEY);
            if (usesPassphrase) {
                file.writeByte(hash);
                file.writeInt(PBKDF2_ITERATIONS);
                file.write(salt);
            }
            file.writeInt(iv.length);
            file.write(iv);
            file.write(encrypted);
        }
        return outputFile;
    }

    // ------------------------------------------------------------------- read

    /** True when a payload can only be opened with the passphrase that sealed it. */
    public static boolean requiresPassphrase(byte[] payload) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new ByteArrayInputStream(payload)))) {
            byte[] header = new byte[MAGIC2.length];
            in.readFully(header);
            if (!java.util.Arrays.equals(header, MAGIC2)) return false; // legacy: device key
            return in.readByte() == KDF_PASSPHRASE;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decrypts into {@code target} and returns the plain database bytes written.
     * Throws {@link PassphraseRequiredException} when the file needs a passphrase
     * that was not supplied, and a plain {@link java.io.IOException} when the file
     * is sealed with another device's key, damaged, or from a newer app version.
     */
    public static byte[] decrypt(File target, byte[] payload, @Nullable String passphrase)
            throws Exception {
        DataInputStream in = new DataInputStream(
                new BufferedInputStream(new ByteArrayInputStream(payload)));
        byte[] iv;
        byte[] encrypted;
        SecretKey key;
        boolean sealedWithPassphrase = false;
        try {
            byte[] header = new byte[MAGIC2.length];
            in.readFully(header);
            boolean modern = java.util.Arrays.equals(header, MAGIC2);
            if (!modern && !java.util.Arrays.equals(header, MAGIC)) {
                throw new java.io.IOException("This is not a QuickTap backup file.");
            }
            byte kdf = KDF_DEVICE_KEY;
            int hash = 0;
            int iterations = 0;
            byte[] salt = null;
            if (modern) {
                kdf = in.readByte();
                sealedWithPassphrase = kdf == KDF_PASSPHRASE;
                if (sealedWithPassphrase) {
                    hash = in.readByte();
                    iterations = in.readInt();
                    salt = new byte[SALT_LENGTH];
                    in.readFully(salt);
                }
            }
            int ivLength = in.readInt();
            if (ivLength < 12 || ivLength > 32) throw new java.io.IOException("Invalid backup file.");
            iv = new byte[ivLength];
            in.readFully(iv);
            encrypted = readAll(in);

            if (sealedWithPassphrase) {
                if (passphrase == null || passphrase.isEmpty()) throw new PassphraseRequiredException();
                key = passphraseKey(passphrase, hash, iterations, salt);
            } else {
                if (passphrase != null && !passphrase.isEmpty()) {
                    throw new java.io.IOException(
                            "This backup was not protected by a passphrase — leave it empty.");
                }
                key = deviceKey();
            }
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }

        byte[] plain;
        try {
            plain = aes(Cipher.DECRYPT_MODE, key, iv, encrypted);
        } catch (javax.crypto.AEADBadTagException bad) {
            throw wrongKey(sealedWithPassphrase, passphrase);
        } catch (javax.crypto.BadPaddingException bad) {
            throw wrongKey(sealedWithPassphrase, passphrase);
        }

        DataInputStream body = new DataInputStream(
                new BufferedInputStream(new ByteArrayInputStream(plain)));
        byte[] inner = new byte[MAGIC.length];
        body.readFully(inner);
        if (!java.util.Arrays.equals(inner, MAGIC)) throw new java.io.IOException("Invalid backup file.");
        JSONObject meta = new JSONObject(body.readUTF());
        if (meta.optInt("backup_version", 0) != BACKUP_VERSION) {
            throw new java.io.IOException("Unsupported backup version.");
        }
        if (meta.optInt("schema_version", 0) > SCHEMA_VERSION) {
            throw new java.io.IOException("This backup came from a newer QuickTap version.");
        }
        int size = body.readInt();
        if (size <= 0 || size > MAX_DB_BYTES) throw new java.io.IOException("Invalid backup size.");
        byte[] database = new byte[size];
        body.readFully(database);
        String expected = meta.optString("sha256", "");
        if (!expected.isEmpty() && !expected.equals(sha256(database))) {
            throw new java.io.IOException("Backup integrity check failed.");
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(database);
        }
        return database;
    }

    /** Signals "ask the user for the passphrase and try again". */
    public static final class PassphraseRequiredException extends Exception {
        public PassphraseRequiredException() { super("passphrase required"); }
    }

    private static Exception wrongKey(boolean sealedWithPassphrase, @Nullable String passphrase) {
        if (sealedWithPassphrase || (passphrase != null && !passphrase.isEmpty())) {
            return new java.io.IOException("Wrong passphrase for this backup.");
        }
        return new java.io.IOException("This backup was sealed on another device "
                + "and can only be restored there.");
    }

    // ------------------------------------------------------------------ keys

    /** Hardware-backed AES key that never leaves this device. Requires Android 6+. */
    public static SecretKey deviceKey() throws Exception {
        if (Build.VERSION.SDK_INT < 23) {
            throw new java.io.IOException("Encrypted backups require Android 6 or newer.");
        }
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(256);
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    /** 0 when PBKDF2-HmacSHA256 is available, otherwise 1 (older devices). */
    private static int hashAlgorithm() {
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return 0;
        } catch (Exception e) {
            return 1;
        }
    }

    private static SecretKey passphraseKey(String passphrase, int hash, int iterations,
                                           byte[] salt) throws Exception {
        String algorithm = hash == 0 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        // The passphrase is normalised so typing it on another keyboard layout does
        // not silently produce a different key; surrounding spaces are kept.
        char[] chars = passphrase.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt,
                Math.max(1000, iterations), 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(algorithm);
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            java.util.Arrays.fill(chars, ' ');
        }
    }

    private static byte[] aes(int mode, SecretKey key, byte[] iv, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(input);
    }

    // --------------------------------------------------------------- helpers

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    public static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
