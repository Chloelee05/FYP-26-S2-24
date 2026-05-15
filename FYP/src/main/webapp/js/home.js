/**
 * SCRUM-82 / SCRUM-178 — homepage search validation, category filters, auction countdowns.
 */
(function () {
  'use strict';

  function parseEndDate(iso) {
    var t = Date.parse(iso);
    return isNaN(t) ? null : t;
  }

  function formatRemaining(ms) {
    if (ms <= 0) return 'Ended';
    var s = Math.floor(ms / 1000);
    var d = Math.floor(s / 86400);
    s -= d * 86400;
    var h = Math.floor(s / 3600);
    s -= h * 3600;
    var m = Math.floor(s / 60);
    s -= m * 60;
    if (d > 0) return d + 'd ' + h + 'h ' + m + 'm ' + s + 's';
    return h + 'h ' + m + 'm ' + s + 's';
  }

  function initCountdowns() {
    var els = document.querySelectorAll('.auction-countdown');
    els.forEach(function (el) {
      if (!el.getAttribute('data-end') && el.getAttribute('data-hours')) {
        var hours = parseFloat(el.getAttribute('data-hours'));
        if (!isNaN(hours)) {
          el.setAttribute('data-end', new Date(Date.now() + hours * 3600000).toISOString());
        }
      }
    });
    els = document.querySelectorAll('.auction-countdown[data-end]');
    function tick() {
      var now = Date.now();
      els.forEach(function (el) {
        var end = parseEndDate(el.getAttribute('data-end'));
        if (end == null) return;
        el.textContent = 'End in: ' + formatRemaining(end - now);
      });
    }
    tick();
    setInterval(tick, 1000);
  }

  function wireSearch() {
    var form = document.getElementById('homeSearchForm');
    var input = document.getElementById('homeSearchInput');
    var group = document.querySelector('.home-search-group');
    var feedback = document.getElementById('homeSearchFeedback');
    var wrap = document.querySelector('.home-search-wrap');
    if (!form || !input) return;

    function clearInvalid() {
      input.classList.remove('is-invalid');
      if (group) group.classList.remove('is-invalid');
      if (feedback) feedback.textContent = '';
      if (wrap) wrap.classList.remove('was-invalid');
    }

    input.addEventListener('input', clearInvalid);

    form.addEventListener('submit', function (e) {
      var q = (input.value || '').trim();
      if (q.length < 2) {
        e.preventDefault();
        input.classList.add('is-invalid');
        if (group) group.classList.add('is-invalid');
        if (wrap) wrap.classList.add('was-invalid');
        if (feedback) {
          feedback.textContent =
              q.length === 0 ? 'Enter a search term.' : 'Type at least 2 characters to search.';
          feedback.style.display = 'block';
        }
        input.focus();
        return;
      }
      clearInvalid();
    });
  }

  function setFilter(category) {
    var cards = document.querySelectorAll('.auction-card[data-category]');
    var showAll = !category || category === 'all';
    cards.forEach(function (card) {
      var cat = (card.getAttribute('data-category') || '').toLowerCase();
      var match = showAll || cat === category.toLowerCase();
      card.classList.toggle('is-muted', !match);
      var col = card.closest('.col');
      if (col) {
        col.classList.toggle('d-none', !match);
      }
    });
  }

  function wireCategoryFilters() {
    document.querySelectorAll('[data-home-category]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        var raw = el.getAttribute('data-home-category');
        if (!raw) return;
        e.preventDefault();
        setFilter(raw === 'all' ? 'all' : raw);
        document.querySelectorAll('[data-home-category].btn').forEach(function (btn) {
          var br = btn.getAttribute('data-home-category');
          btn.classList.toggle('active', br === raw);
        });
        var t = document.getElementById('trending');
        if (t) {
          t.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      });
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    initCountdowns();
    wireSearch();
    wireCategoryFilters();
  });
})();
