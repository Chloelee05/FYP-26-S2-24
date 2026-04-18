// AuctionHub - Main JavaScript

document.addEventListener('DOMContentLoaded', function () {
    initCountdowns();
});

/**
 * Finds all elements with data-end-time and starts a live countdown.
 */
function initCountdowns() {
    document.querySelectorAll('[data-end-time]').forEach(function (el) {
        var endTime = new Date(el.getAttribute('data-end-time')).getTime();
        updateCountdown(el, endTime);
        setInterval(function () {
            updateCountdown(el, endTime);
        }, 1000);
    });
}

function updateCountdown(el, endTime) {
    var now = new Date().getTime();
    var diff = endTime - now;

    if (diff <= 0) {
        el.textContent = 'Auction Ended';
        el.classList.add('text-danger');
        return;
    }

    var days = Math.floor(diff / (1000 * 60 * 60 * 24));
    var hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
    var minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
    var seconds = Math.floor((diff % (1000 * 60)) / 1000);

    var parts = [];
    if (days > 0) parts.push(days + 'd');
    parts.push(pad(hours) + 'h');
    parts.push(pad(minutes) + 'm');
    parts.push(pad(seconds) + 's');

    el.textContent = parts.join(' ');
}

function pad(n) {
    return n < 10 ? '0' + n : n;
}
