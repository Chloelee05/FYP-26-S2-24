/**
 * Client-side validation aligned with com.auction.util.InputValidator (SCRUM-176).
 */
(function () {
  'use strict';

  var EMAIL_RE = /^[a-zA-Z0-9_+&*-]+(?:\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}$/;
  var SPECIAL_RE = /[!@#$%^&*()_+\-=[\]{}|;:,.?]/;

  function setInvalid(inputEl, feedbackEl, message) {
    if (!inputEl) return;
    inputEl.classList.add('is-invalid');
    if (feedbackEl) {
      feedbackEl.textContent = message;
      feedbackEl.classList.add('d-block');
    }
  }

  function clearInvalid(inputEl, feedbackEl) {
    if (inputEl) inputEl.classList.remove('is-invalid');
    if (feedbackEl) {
      feedbackEl.textContent = '';
      feedbackEl.classList.remove('d-block');
    }
  }

  function validateEmail(value) {
    if (!value || !String(value).trim()) return 'Email is required.';
    var t = String(value).trim();
    if (t.length > 254) return 'Email is too long.';
    if (!EMAIL_RE.test(t)) return 'Please enter a valid Email address.';
    return null;
  }

  function validatePasswordPolicy(pw) {
    if (pw == null || String(pw) === '') return 'Password is required.';
    var p = String(pw);
    if (p.length < 8) return 'Password must be at least 8 characters.';
    if (p.length > 128) return 'Password must be at most 128 characters.';
    if (!/[A-Z]/.test(p)) return 'Password must include at least one uppercase letter.';
    if (!/[a-z]/.test(p)) return 'Password must include at least one lowercase letter.';
    if (!/[0-9]/.test(p)) return 'Password must include at least one digit.';
    if (!SPECIAL_RE.test(p)) {
      return 'Password must include at least one special character (!@#$%^&* etc.).';
    }
    return null;
  }

  function validateDisplayName(name) {
    if (!name || !String(name).trim()) return 'Full name is required.';
    var t = String(name).trim();
    if (t.length < 2) return 'Full name must be at least 2 characters.';
    if (t.length > 64) return 'Full name must be at most 64 characters.';
    // Avoid \\p{L} (needs modern JS); server still enforces InputValidator.getDisplayNameViolation.
    if (/[\r\n\x00-\x08\x0B\x0C\x0E-\x1F]/.test(t)) {
      return 'Full name contains invalid characters.';
    }
    return null;
  }

  function wireLogin(form) {
    if (!form) return;
    var email = form.querySelector('#email');
    var password = form.querySelector('#password');
    var fe = {
      email: form.querySelector('[data-feedback="email"]'),
      password: form.querySelector('[data-feedback="password"]')
    };

    form.addEventListener('submit', function (e) {
      var ok = true;
      clearInvalid(email, fe.email);
      clearInvalid(password, fe.password);

      var ev = validateEmail(email && email.value);
      if (ev) {
        setInvalid(email, fe.email, ev);
        ok = false;
      }
      if (!password || !String(password.value)) {
        setInvalid(password, fe.password, 'Password is required.');
        ok = false;
      }
      if (!ok) e.preventDefault();
    });
  }

  function wireRegister(form) {
    if (!form) return;
    var fullName = form.querySelector('#username');
    var email = form.querySelector('#email');
    var password = form.querySelector('#password');
    var confirm = form.querySelector('#confirmPassword');
    var terms = form.querySelector('#termsAccept');
    var fe = {
      username: form.querySelector('[data-feedback="username"]'),
      email: form.querySelector('[data-feedback="email"]'),
      password: form.querySelector('[data-feedback="password"]'),
      confirm: form.querySelector('[data-feedback="confirmPassword"]'),
      terms: form.querySelector('[data-feedback="termsAccept"]')
    };

    form.addEventListener('submit', function (e) {
      var ok = true;
      clearInvalid(fullName, fe.username);
      clearInvalid(email, fe.email);
      clearInvalid(password, fe.password);
      clearInvalid(confirm, fe.confirm);
      if (terms) terms.classList.remove('is-invalid');
      if (fe.terms) {
        fe.terms.textContent = '';
        fe.terms.classList.remove('d-block');
      }

      var nv = validateDisplayName(fullName && fullName.value);
      if (nv) {
        setInvalid(fullName, fe.username, nv);
        ok = false;
      }
      var em = validateEmail(email && email.value);
      if (em) {
        setInvalid(email, fe.email, em);
        ok = false;
      }
      var pv = validatePasswordPolicy(password && password.value);
      if (pv) {
        setInvalid(password, fe.password, pv);
        ok = false;
      }
      if (!confirm || String(password && password.value) !== String(confirm.value)) {
        setInvalid(confirm, fe.confirm, 'Passwords do not match.');
        ok = false;
      }
      if (!terms || !terms.checked) {
        if (terms) terms.classList.add('is-invalid');
        if (fe.terms) {
          fe.terms.textContent = 'You must accept the terms to continue.';
          fe.terms.classList.add('d-block');
        }
        ok = false;
      }
      if (!ok) e.preventDefault();
    });
  }

  function wireForgot(form) {
    if (!form) return;
    var identifier = form.querySelector('#identifier');
    var fe = form.querySelector('[data-feedback="identifier"]');

    form.addEventListener('submit', function (e) {
      clearInvalid(identifier, fe);
      var em = validateEmail(identifier && identifier.value);
      if (em) {
        setInvalid(identifier, fe, em);
        e.preventDefault();
      }
    });
  }

  function wireReset(form) {
    if (!form) return;
    var otp = form.querySelector('#otp');
    var np = form.querySelector('#newPassword');
    var cnp = form.querySelector('#confirmNewPassword');
    var fe = {
      otp: form.querySelector('[data-feedback="otp"]'),
      np: form.querySelector('[data-feedback="newPassword"]'),
      cnp: form.querySelector('[data-feedback="confirmNewPassword"]')
    };

    form.addEventListener('submit', function (e) {
      var ok = true;
      clearInvalid(otp, fe.otp);
      clearInvalid(np, fe.np);
      clearInvalid(cnp, fe.cnp);

      if (!otp || !String(otp.value || '').trim()) {
        setInvalid(otp, fe.otp, 'OTP is required.');
        ok = false;
      }
      var pv = validatePasswordPolicy(np && np.value);
      if (pv) {
        setInvalid(np, fe.np, pv);
        ok = false;
      }
      if (!cnp || String(np && np.value) !== String(cnp.value)) {
        setInvalid(cnp, fe.cnp, 'Passwords do not match.');
        ok = false;
      }
      if (!ok) e.preventDefault();
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    wireLogin(document.getElementById('loginForm'));
    wireRegister(document.getElementById('registerForm'));
    wireForgot(document.getElementById('forgotPasswordForm'));
    wireReset(document.getElementById('resetPasswordForm'));
  });
})();
