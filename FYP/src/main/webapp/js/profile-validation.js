/**
 * SCRUM-182: client-side profile form checks (complement server {@link com.auction.util.InputValidator}).
 */
(function () {
    'use strict';

    var DISPLAY_MIN = 2;
    var DISPLAY_MAX = 64;
    var ADDRESS_MAX = 500;
    var URL_MAX = 512;

    var DISPLAY_RE = /^[\p{L}0-9][\p{L}0-9 '\-.]{1,63}$/u;
    var EMAIL_RE = /^[a-zA-Z0-9_+&*-]+(?:\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}$/;
    var PHONE_RE = /^\+?[0-9]{8,15}$/;

    function feedback(input, message) {
        var group = input.closest('.mb-3') || input.parentElement;
        var el = group ? group.querySelector('.invalid-feedback') : null;
        if (message) {
            input.setCustomValidity(message);
            input.classList.add('is-invalid');
            if (el) {
                el.textContent = message;
                el.classList.add('d-block');
            }
        } else {
            input.setCustomValidity('');
            input.classList.remove('is-invalid');
            if (el) {
                el.textContent = '';
                el.classList.remove('d-block');
            }
        }
    }

    function validateDisplayName(v, input) {
        if (!v || v.trim().length < DISPLAY_MIN) {
            feedback(input, 'Display name must be at least ' + DISPLAY_MIN + ' characters.');
            return false;
        }
        if (v.trim().length > DISPLAY_MAX) {
            feedback(input, 'Display name must be at most ' + DISPLAY_MAX + ' characters.');
            return false;
        }
        if (!DISPLAY_RE.test(v.trim())) {
            feedback(input, 'Display name contains invalid characters.');
            return false;
        }
        feedback(input, '');
        return true;
    }

    function validateEmail(v, input) {
        if (!v || !EMAIL_RE.test(v.trim())) {
            feedback(input, 'Please enter a valid email address.');
            return false;
        }
        feedback(input, '');
        return true;
    }

    function validatePhone(v, input) {
        if (!v || v.trim() === '') {
            feedback(input, '');
            return true;
        }
        var t = v.trim().replace(/\s/g, '');
        if (!PHONE_RE.test(t)) {
            feedback(input, 'Please enter a valid phone number (8–15 digits, optional leading +).');
            return false;
        }
        feedback(input, '');
        return true;
    }

    function validateAddress(v, input) {
        if (!v || v.trim() === '') {
            feedback(input, '');
            return true;
        }
        if (v.trim().length > ADDRESS_MAX) {
            feedback(input, 'Address must be at most ' + ADDRESS_MAX + ' characters.');
            return false;
        }
        feedback(input, '');
        return true;
    }

    function validateImageUrl(v, input) {
        if (!v || v.trim() === '') {
            feedback(input, '');
            return true;
        }
        var t = v.trim();
        if (t.length > URL_MAX) {
            feedback(input, 'Image URL is too long.');
            return false;
        }
        if (!t.startsWith('https://')) {
            feedback(input, 'Image URL must use https://');
            return false;
        }
        try {
            new URL(t);
        } catch (e) {
            feedback(input, 'Image URL is not a valid URL.');
            return false;
        }
        feedback(input, '');
        return true;
    }

    document.addEventListener('DOMContentLoaded', function () {
        var form = document.getElementById('profileEditForm');
        if (!form) return;

        var username = document.getElementById('username');
        var email = document.getElementById('email');
        var phone = document.getElementById('phone');
        var address = document.getElementById('address');
        var imgUrl = document.getElementById('profileImageUrl');

        function runAll() {
            var ok = true;
            ok = validateDisplayName(username.value, username) && ok;
            ok = validateEmail(email.value, email) && ok;
            ok = validatePhone(phone.value, phone) && ok;
            ok = validateAddress(address.value, address) && ok;
            ok = validateImageUrl(imgUrl.value, imgUrl) && ok;
            return ok;
        }

        ['input', 'blur'].forEach(function (ev) {
            username.addEventListener(ev, function () { validateDisplayName(username.value, username); });
            email.addEventListener(ev, function () { validateEmail(email.value, email); });
            phone.addEventListener(ev, function () { validatePhone(phone.value, phone); });
            address.addEventListener(ev, function () { validateAddress(address.value, address); });
            imgUrl.addEventListener(ev, function () { validateImageUrl(imgUrl.value, imgUrl); });
        });

        form.addEventListener('submit', function (e) {
            if (!runAll()) {
                e.preventDefault();
                e.stopPropagation();
            }
            form.classList.add('was-validated');
        });
    });
})();
