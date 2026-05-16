<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title><c:out value="${auction.title}"/> — AuctionHub</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <style>
        body { background-color: #f8f9fa; }
        .auction-image-main { object-fit: cover; height: 420px; width: 100%; border-radius: .5rem; }
        .bid-card { background: #fff; border: 1.5px solid #dee2e6; border-radius: .75rem; }
        .bid-card .current-bid { font-size: 2rem; font-weight: 700; color: #198754; }
        .countdown { font-size: 1.1rem; font-weight: 600; color: #dc3545; }
        .thumbnail-strip img { width: 72px; height: 72px; object-fit: cover; cursor: pointer;
                               border-radius: .375rem; border: 2px solid transparent; }
        .thumbnail-strip img:hover, .thumbnail-strip img.active { border-color: #0d6efd; }
    </style>
</head>
<body>
<%@ include file="/WEB-INF/includes/home-navbar.jsp" %>

<div class="container py-4">

    <%-- Flash messages --%>
    <c:if test="${not empty bidFlash}">
        <div class="alert alert-success alert-dismissible fade show" role="alert">
            <i class="bi bi-check-circle-fill me-2"></i><c:out value="${bidFlash}"/>
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    </c:if>
    <c:if test="${not empty bidFlashError}">
        <div class="alert alert-danger alert-dismissible fade show" role="alert">
            <i class="bi bi-exclamation-triangle-fill me-2"></i><c:out value="${bidFlashError}"/>
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    </c:if>

    <div class="row g-4">

        <%-- Left column: images --%>
        <div class="col-lg-7">
            <c:choose>
                <c:when test="${not empty auction.imageUrls}">
                    <img id="mainImage"
                         src="${pageContext.request.contextPath}/uploads/<c:out value="${auction.imageUrls[0]}"/>"
                         alt="Auction image" class="auction-image-main mb-2 shadow-sm">
                    <c:if test="${auction.imageUrls.size() > 1}">
                        <div class="d-flex gap-2 thumbnail-strip flex-wrap">
                            <c:forEach var="img" items="${auction.imageUrls}" varStatus="loop">
                                <img src="${pageContext.request.contextPath}/uploads/<c:out value="${img}"/>"
                                     alt="Thumbnail ${loop.index + 1}"
                                     class="${loop.index == 0 ? 'active' : ''}"
                                     onclick="switchImage(this)">
                            </c:forEach>
                        </div>
                    </c:if>
                </c:when>
                <c:otherwise>
                    <div class="d-flex align-items-center justify-content-center bg-light border rounded"
                         style="height:420px;">
                        <i class="bi bi-image text-muted" style="font-size:5rem;"></i>
                    </div>
                </c:otherwise>
            </c:choose>
        </div>

        <%-- Right column: details + bid --%>
        <div class="col-lg-5">
            <span class="badge bg-secondary mb-2"><c:out value="${auction.category}"/></span>
            <h1 class="h3 fw-bold mb-1"><c:out value="${auction.title}"/></h1>
            <p class="text-muted small mb-3">
                <i class="bi bi-person me-1"></i>Sold by <strong><c:out value="${auction.sellerUsername}"/></strong>
            </p>

            <%-- Bid card --%>
            <div class="bid-card p-4 mb-3">
                <div class="mb-1 text-muted small">Current bid</div>
                <div class="current-bid mb-1">
                    $<fmt:formatNumber value="${auction.currentBid}" pattern="#,##0.00"/>
                </div>
                <div class="text-muted small mb-2">
                    Starting price: $<fmt:formatNumber value="${auction.startingPrice}" pattern="#,##0.00"/>
                    &nbsp;&bull;&nbsp;
                    <c:out value="${auction.bidCount}"/> bid<c:if test="${auction.bidCount != 1}">s</c:if>
                </div>

                <c:choose>
                    <c:when test="${auction.open}">
                        <div id="countdown" class="countdown mb-3">
                            <i class="bi bi-clock me-1"></i>
                            Ends <span id="endTimeDisplay"></span>
                        </div>
                    </c:when>
                    <c:otherwise>
                        <div class="badge bg-danger fs-6 mb-3 px-3 py-2">
                            <i class="bi bi-lock-fill me-1"></i>Auction Ended
                        </div>
                    </c:otherwise>
                </c:choose>

                <c:choose>
                    <c:when test="${canBid}">
                        <%-- Bid form — SCRUM-264 --%>
                        <form method="post"
                              action="${pageContext.request.contextPath}/protected/bid"
                              id="bidForm">
                            <input type="hidden" name="auctionId" value="${auction.auctionId}">
                            <div class="input-group mb-2">
                                <span class="input-group-text">$</span>
                                <input type="number" id="bidAmount" name="bidAmount"
                                       class="form-control"
                                       placeholder="Enter bid amount"
                                       min="0.01" step="0.01"
                                       required>
                            </div>
                            <div id="bidHint" class="form-text text-muted mb-2">
                                Enter an amount greater than
                                $<fmt:formatNumber value="${auction.currentBid}" pattern="#,##0.00"/>.
                                <c:if test="${not empty auction.maxPrice}">
                                    Maximum: $<fmt:formatNumber value="${auction.maxPrice}" pattern="#,##0.00"/>.
                                </c:if>
                            </div>
                            <button type="button" class="btn btn-success w-100"
                                    onclick="confirmBid()">
                                <i class="bi bi-hammer me-2"></i>Place Bid
                            </button>
                        </form>
                    </c:when>
                    <c:when test="${isSelf}">
                        <div class="alert alert-warning mb-0">
                            <i class="bi bi-info-circle me-1"></i>
                            You cannot bid on your own auction.
                        </div>
                    </c:when>
                    <c:when test="${not loggedIn}">
                        <a href="${pageContext.request.contextPath}/login"
                           class="btn btn-outline-primary w-100">
                            <i class="bi bi-box-arrow-in-right me-2"></i>Log in to bid
                        </a>
                    </c:when>
                    <c:otherwise>
                        <div class="alert alert-secondary mb-0">
                            <i class="bi bi-info-circle me-1"></i>
                            <c:choose>
                                <c:when test="${not auction.open}">This auction has ended.</c:when>
                                <c:otherwise>You are not eligible to bid on this auction.</c:otherwise>
                            </c:choose>
                        </div>
                    </c:otherwise>
                </c:choose>
            </div><%-- /bid-card --%>
        </div><%-- /col --%>
    </div><%-- /row --%>

    <%-- Description --%>
    <div class="row mt-2">
        <div class="col-lg-7">
            <h5 class="fw-semibold mb-2">Description</h5>
            <p class="text-body-secondary" style="white-space:pre-line;">
                <c:out value="${auction.description}"/>
            </p>
        </div>
    </div>

</div><%-- /container --%>

<%-- Bid confirmation modal --%>
<div class="modal fade" id="confirmModal" tabindex="-1" aria-hidden="true">
    <div class="modal-dialog modal-dialog-centered">
        <div class="modal-content">
            <div class="modal-header border-0">
                <h5 class="modal-title fw-bold">Confirm Your Bid</h5>
                <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
            </div>
            <div class="modal-body text-center py-4">
                <i class="bi bi-hammer text-success" style="font-size:3rem;"></i>
                <p class="mt-3 fs-5">You are about to bid</p>
                <p class="fs-3 fw-bold text-success" id="confirmAmount"></p>
                <p class="text-muted small">on <strong><c:out value="${auction.title}"/></strong></p>
                <p class="text-muted small">This action cannot be undone.</p>
            </div>
            <div class="modal-footer border-0 justify-content-center gap-2">
                <button type="button" class="btn btn-outline-secondary"
                        data-bs-dismiss="modal">Cancel</button>
                <button type="button" class="btn btn-success px-4"
                        onclick="submitBid()">Confirm Bid</button>
            </div>
        </div>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script>
    // Countdown timer
    (function () {
        const endTimeEl = document.getElementById('endTimeDisplay');
        if (!endTimeEl) return;
        const endMs = <c:out value="${auction.endDate.toEpochMilli()}"/>;
        function tick() {
            const diff = endMs - Date.now();
            if (diff <= 0) { endTimeEl.textContent = 'Auction ended'; return; }
            const d = Math.floor(diff / 86400000);
            const h = Math.floor((diff % 86400000) / 3600000);
            const m = Math.floor((diff % 3600000) / 60000);
            const s = Math.floor((diff % 60000) / 1000);
            endTimeEl.textContent = d > 0
                ? d + 'd ' + h + 'h ' + m + 'm'
                : h + 'h ' + String(m).padStart(2,'0') + 'm ' + String(s).padStart(2,'0') + 's';
        }
        tick();
        setInterval(tick, 1000);
    })();

    // Image switcher
    function switchImage(thumb) {
        document.getElementById('mainImage').src = thumb.src;
        document.querySelectorAll('.thumbnail-strip img').forEach(i => i.classList.remove('active'));
        thumb.classList.add('active');
    }

    // Confirm modal
    function confirmBid() {
        const amount = document.getElementById('bidAmount').value;
        if (!amount || parseFloat(amount) <= 0) {
            document.getElementById('bidAmount').reportValidity();
            return;
        }
        document.getElementById('confirmAmount').textContent =
            '$' + parseFloat(amount).toFixed(2);
        new bootstrap.Modal(document.getElementById('confirmModal')).show();
    }

    function submitBid() {
        document.getElementById('bidForm').submit();
    }
</script>
</body>
</html>
