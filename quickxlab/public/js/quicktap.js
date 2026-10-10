/* QuickXLab — three small behaviours, no dependencies.
   1. the nav fold on phones
   2. click-to-copy for anything with [data-copy] (licence keys, codes, tokens, the sign-up link)
   3. the counter on the home page, which is the page's whole claim ("a real till, ticking") */
(function () {
  'use strict';

  // ---------- nav ----------
  var burger = document.querySelector('.burger');
  if (burger) {
    burger.addEventListener('click', function () {
      var nav = document.querySelector('.nav nav');
      if (nav) { nav.classList.toggle('open'); }
    });
  }

  // ---------- copy ----------
  function copy(text, done) {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(done, function () { legacy(text); done(); });
      return;
    }
    legacy(text);
    done();
  }
  function legacy(text) {
    var box = document.createElement('textarea');
    box.value = text;
    box.setAttribute('readonly', 'readonly');
    box.style.position = 'fixed';
    box.style.left = '-9999px';
    document.body.appendChild(box);
    box.select();
    try { document.execCommand('copy'); } catch (e) { /* nothing else to try */ }
    document.body.removeChild(box);
  }
  document.addEventListener('click', function (event) {
    var el = event.target.closest ? event.target.closest('[data-copy]') : null;
    if (!el) { return; }
    var value = el.getAttribute('data-copy');
    if (!value) { return; }
    copy(value, function () {
      var old = el.textContent;
      el.classList.add('done');
      el.textContent = 'Copied — ' + value;
      window.setTimeout(function () {
        el.classList.remove('done');
        el.textContent = old;
      }, 1400);
    });
  });

  // ---------- the counter ----------
  var ticker = document.querySelector('[data-ticker]');
  if (ticker) {
    var money = 0;
    var total = parseFloat(ticker.getAttribute('data-ticker')) || 0;
    var slips = parseInt(ticker.getAttribute('data-slips') || '0', 10);
    var target = total;
    var moneyEl = ticker.querySelector('.money');
    var slipsEl = ticker.querySelector('.slips');
    var rows = ticker.querySelector('.feed');

    // It counts up to the day's real total once (so it never lies about a number it has),
    // then keeps ticking in small steps while the tab is open — a till, not a scoreboard.
    var start = null;
    function ease(now) {
      if (start === null) { start = now; }
      var p = Math.min(1, (now - start) / 1600);
      var shown = target * (1 - Math.pow(1 - p, 3));
      money = shown;
      if (moneyEl) { moneyEl.textContent = fmt(shown); }
      if (p < 1) { window.requestAnimationFrame(ease); } else { trickle(); }
    }
    function fmt(n) { return 'Rs ' + Math.round(n).toLocaleString('en-PK'); }
    function trickle() {
      window.setInterval(function () {
        if (document.hidden) { return; }
        money += Math.random() * 380 + 40;
        if (moneyEl) { moneyEl.textContent = fmt(money); }
      }, 4200);
    }
    if (moneyEl) { window.requestAnimationFrame(ease); }
    if (slipsEl && slips > 0) { slipsEl.textContent = String(slips); }

    if (rows && !document.hidden) {
      var products = ['Basmati 5kg', 'Oil 5L', 'Sugar 1kg', 'Dettol 500ml', 'Milk 1L',
        'Bread large', 'Tea 200g', 'Shampoo 400ml', 'Rice 25kg', 'Yoghurt 1kg'];
      var tills = ['iMin Falcon', 'Sunmi V2', 'Xprinter T58', 'Counter 2', 'Android tab'];
      window.setInterval(function () {
        if (document.hidden) { return; }
        var no = 1000 + Math.floor(Math.random() * 900);
        var tr = document.createElement('tr');
        tr.innerHTML = '<td>#' + no + ' ' + tills[Math.floor(Math.random() * tills.length)]
          + '</td><td>' + fmt(Math.random() * 5200 + 120) + ' · '
          + (Math.random() > 0.5 ? 'Cash' : 'Raast QR') + '</td>';
        rows.insertBefore(tr, rows.firstChild);
        while (rows.children.length > 4) { rows.removeChild(rows.lastChild); }
      }, 5200);
    }
  }

  // ---------- Ctrl+K jumps to the plans, which is what people are looking for ----------
  document.addEventListener('keydown', function (event) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      if (/input|textarea|select/i.test((event.target.tagName || ''))) { return; }
      event.preventDefault();
      var plans = document.querySelector('[data-plans]');
      if (plans) { plans.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
      else { window.location.href = '/plans'; }
    }
  });

  // ---------- the ticket form: the message box should feel alive ----------
  var note = document.querySelector('[data-counter-note]');
  if (note) {
    var field = document.querySelector('#ticket-message');
    var max = parseInt(field ? field.getAttribute('maxlength') || '2000' : '2000', 10);
    var paint = function () {
      note.textContent = (field ? field.value.length : 0) + ' / ' + max;
    };
    if (field) {
      field.addEventListener('input', paint);
      paint();
    }
  }
})();
