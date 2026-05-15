/** Client-side checks for admin forms (SCRUM-180). */
(function () {
    'use strict';

    function setInvalid(el, msg) {
        el.classList.add('is-invalid');
        var f = el.closest('td, .mb-3') && el.closest('td, .mb-3').querySelector('.invalid-feedback');
        if (f) {
            f.textContent = msg;
            f.classList.add('d-block');
        }
    }

    function clearInvalid(el) {
        el.classList.remove('is-invalid');
    }

    function bindUserActions() {
        document.querySelectorAll('form[data-admin-user-action]').forEach(function (form) {
            form.addEventListener('submit', function (e) {
                var btn = form.querySelector('[data-confirm]');
                if (btn && !window.confirm(btn.getAttribute('data-confirm'))) {
                    e.preventDefault();
                }
            });
        });
    }

    function bindListingActions() {
        document.querySelectorAll('form[data-admin-listing-action]').forEach(function (form) {
            form.addEventListener('submit', function (e) {
                var aid = form.querySelector('input[name="auctionId"]');
                if (!aid || !aid.value.trim()) {
                    e.preventDefault();
                    if (aid) setInvalid(aid, 'Missing listing id.');
                    return;
                }
                clearInvalid(aid);
                var btn = form.querySelector('[data-confirm]');
                if (btn && !window.confirm(btn.getAttribute('data-confirm'))) {
                    e.preventDefault();
                }
            });
        });
    }

    function bindReportCards() {
        document.querySelectorAll('[data-report-card]').forEach(function (card) {
            card.addEventListener('click', function () {
                var title = card.getAttribute('data-report-title') || 'Report';
                if (!title) return;
                window.alert(title + ': export will connect to a backend endpoint in a later sprint. (Validation: action acknowledged.)');
            });
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        bindUserActions();
        bindListingActions();
        bindReportCards();
    });
})();
