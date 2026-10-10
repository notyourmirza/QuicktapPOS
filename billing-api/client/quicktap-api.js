/**
 * QuickTap billing — one-file browser + Node client.
 *
 *   <script type="module">
 *     import { QuickTap } from './quicktap-api.js';
 *     const qt = new QuickTap({ baseUrl: 'https://your-domain.com/api', token: 'qt_pat_…' });
 *     const { products } = await qt.catalog();
 *     await qt.saveBill({ items: [{ name: 'Chicken Roll', price: 250, qty: 2 }] });
 *   </script>
 *
 * No build step, no dependencies: fetch() in the browser and on Node 18+. A refused
 * call rejects with QuickTapError, whose `message` is written for the shopkeeper and can
 * be shown as-is, and whose `code` is what you branch on (LICENSE_EXPIRED, SCOPE_DENIED…).
 */

export class QuickTapError extends Error {
  constructor(message, code = 'ERROR', status = 0) {
    super(message);
    this.name = 'QuickTapError';
    this.code = code;
    this.status = status;
  }
}

export class QuickTap {
  /** @param {{baseUrl:string, token?:string, appId?:string, apiKey?:string, deviceId?:string, fetch?:Function}} options */
  constructor(options) {
    const { baseUrl, token, appId, apiKey, deviceId, fetch: fetchImpl } = options || {};
    if (!baseUrl) throw new Error('QuickTap needs a baseUrl');
    if (!token && !(appId && apiKey)) {
      throw new Error('QuickTap needs a token (recommended), or appId and apiKey');
    }
    this.baseUrl = baseUrl.replace(/\/+$/, '') + '/';
    this.token = token || null;
    this.appId = appId || null;
    this.apiKey = apiKey || null;
    this.deviceId = deviceId || null;
    this.fetch = fetchImpl || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
    if (!this.fetch) throw new Error('No fetch() available — Node 18+ or a modern browser');
  }

  headers(extra) {
    const headers = Object.assign({ Accept: 'application/json' }, extra || {});
    if (this.token) headers['X-Api-Token'] = this.token;
    if (this.appId) headers['X-App-Id'] = this.appId;
    if (this.apiKey) headers['X-Api-Key'] = this.apiKey;
    if (this.deviceId) headers['X-Device-Id'] = this.deviceId;
    return headers;
  }

  /** @returns {Promise<object>} the envelope's data */
  async request(path, { method = 'GET', body, query } = {}) {
    let url = this.baseUrl + path.replace(/^\/+/, '');
    if (query) {
      const params = new URLSearchParams();
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== null && value !== '') params.set(key, value);
      }
      const text = params.toString();
      if (text) url += '?' + text;
    }
    const init = { method, headers: this.headers(body ? { 'Content-Type': 'application/json' } : null) };
    if (body) init.body = JSON.stringify(body);

    let response;
    try {
      response = await this.fetch(url, init);
    } catch (error) {
      throw new QuickTapError('The billing server is unreachable. ' + error.message, 'OFFLINE', 0);
    }
    let envelope = null;
    try {
      envelope = await response.json();
    } catch (error) {
      throw new QuickTapError(
        `The billing server answered with something that is not JSON (HTTP ${response.status}).`,
        'BAD_RESPONSE', response.status);
    }
    if (!envelope || !envelope.success) {
      throw new QuickTapError(
        (envelope && envelope.message) || 'The billing server refused the request.',
        (envelope && envelope.code) || 'ERROR', response.status);
    }
    return envelope.data || {};
  }

  // ---------------------------------------------------------------------- shop

  shop() { return this.request('v1/shop'); }

  /** Cheap check before a counter lets someone bill. */
  async mayBill() {
    const data = await this.shop();
    return !!(data.licence && data.licence.unlocked);
  }

  // ----------------------------------------------------------------- catalogue

  catalog(options = {}) {
    return this.request('v1/catalog', { query: {
      q: options.q, include_deleted: options.includeDeleted ? 1 : undefined } });
  }

  saveProduct(product) { return this.request('v1/catalog/products', { method: 'POST', body: product })
    .then((data) => data.products[0]); }

  saveProducts(products) {
    return this.request('v1/catalog/products', { method: 'POST', body: { products } })
      .then((data) => data.products);
  }

  saveCategory(category) { return this.request('v1/catalog/categories', { method: 'POST', body: category })
    .then((data) => data.categories[0]); }

  // --------------------------------------------------------------------- bills

  bills(query = {}) {
    return this.request('v1/bills', { query: {
      from: query.from, to: query.to, q: query.q,
      limit: query.limit, offset: query.offset,
      include_deleted: query.includeDeleted ? 1 : undefined } });
  }

  /** Pages a range for you, so a report loop cannot miss a page. */
  async *iterateBills(query = {}) {
    const size = query.pageSize || 200;
    let offset = 0;
    for (;;) {
      const data = await this.bills({ ...query, limit: size, offset });
      const rows = data.bills || [];
      if (!rows.length) return;
      for (const row of rows) yield row;
      if (rows.length < size) return;
      offset += rows.length;
    }
  }

  /** @param {object} bill {uuid?, invoice_no?, order_type?, items:[{name,price,qty,product_uuid?}], …} */
  saveBill(bill) { return this.request('v1/bills', { method: 'POST', body: bill })
    .then((data) => data.bill); }

  // ------------------------------------------------------------------- reports

  reportDay(date) { return this.request('v1/reports/day', { query: { date } }); }

  reportRange(from, to) { return this.request('v1/reports/range', { query: { from, to } }); }
}

/** A random uuid for a bill you may need to retry safely. */
export function newUuid() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID().replace(/-/g, '');
  }
  let out = '';
  for (let i = 0; i < 32; i++) out += Math.floor(Math.random() * 16).toString(16);
  return out;
}

/** Money as the till prints it: no thousands separator surprises. */
export function money(amount, currency = '') {
  const value = Number(amount || 0).toFixed(2).replace(/\.00$/, '');
  return currency ? `${currency} ${value}` : value;
}

export default QuickTap;

// CommonJS / plain <script> use, so the file works with or without a bundler.
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { QuickTap, QuickTapError, newUuid, money };
}
if (typeof window !== 'undefined') {
  window.QuickTap = QuickTap;
  window.QuickTapError = QuickTapError;
  window.QuickTapUtil = { newUuid, money };
}
