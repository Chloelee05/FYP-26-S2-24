<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="AuctionHub - Online Auction Platform"/>
</jsp:include>

<!-- Hero Section -->
<div class="hero text-center">
    <div class="container">
        <h1><i class="bi bi-hammer"></i> Welcome to AuctionHub</h1>
        <p class="lead mb-4">Your trusted C2C online auction marketplace. Buy and sell with confidence.</p>
        <div class="d-flex justify-content-center gap-3">
            <a href="${pageContext.request.contextPath}/product/list" class="btn btn-light btn-lg px-4">
                <i class="bi bi-grid"></i> Browse Auctions
            </a>
            <c:if test="${sessionScope.user == null}">
                <a href="${pageContext.request.contextPath}/auth/register" class="btn btn-outline-light btn-lg px-4">
                    <i class="bi bi-person-plus"></i> Join Now
                </a>
            </c:if>
            <c:if test="${sessionScope.user != null && sessionScope.user.seller}">
                <a href="${pageContext.request.contextPath}/product/create" class="btn btn-outline-light btn-lg px-4">
                    <i class="bi bi-plus-circle"></i> Sell an Item
                </a>
            </c:if>
        </div>
    </div>
</div>

<!-- How It Works -->
<section class="mb-5">
    <h3 class="text-center mb-4">How It Works</h3>
    <div class="row g-4">
        <div class="col-md-4">
            <div class="card border-0 shadow-sm text-center p-4 h-100" style="border-radius:16px;">
                <div class="mb-3">
                    <i class="bi bi-person-plus text-primary" style="font-size:3rem;"></i>
                </div>
                <h5>1. Create Account</h5>
                <p class="text-muted">Register as a Buyer or Seller to get started on the platform.</p>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card border-0 shadow-sm text-center p-4 h-100" style="border-radius:16px;">
                <div class="mb-3">
                    <i class="bi bi-search text-success" style="font-size:3rem;"></i>
                </div>
                <h5>2. Find or List Items</h5>
                <p class="text-muted">Browse products and auctions, or list your own items for sale.</p>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card border-0 shadow-sm text-center p-4 h-100" style="border-radius:16px;">
                <div class="mb-3">
                    <i class="bi bi-hammer text-warning" style="font-size:3rem;"></i>
                </div>
                <h5>3. Bid & Win</h5>
                <p class="text-muted">Place bids on items you want. The highest bidder wins when time runs out.</p>
            </div>
        </div>
    </div>
</section>

<!-- Features -->
<section class="mb-5">
    <h3 class="text-center mb-4">Platform Features</h3>
    <div class="row g-3">
        <div class="col-md-6 col-lg-3">
            <div class="d-flex align-items-start p-3">
                <i class="bi bi-shield-check text-primary fs-3 me-3"></i>
                <div>
                    <h6 class="mb-1">Secure Bidding</h6>
                    <small class="text-muted">Safe and transparent auction process</small>
                </div>
            </div>
        </div>
        <div class="col-md-6 col-lg-3">
            <div class="d-flex align-items-start p-3">
                <i class="bi bi-graph-up-arrow text-success fs-3 me-3"></i>
                <div>
                    <h6 class="mb-1">Multiple Strategies</h6>
                    <small class="text-muted">Price Up, Low Start High, Public Bidding</small>
                </div>
            </div>
        </div>
        <div class="col-md-6 col-lg-3">
            <div class="d-flex align-items-start p-3">
                <i class="bi bi-clock text-warning fs-3 me-3"></i>
                <div>
                    <h6 class="mb-1">Real-time Countdown</h6>
                    <small class="text-muted">Live countdown timers on all auctions</small>
                </div>
            </div>
        </div>
        <div class="col-md-6 col-lg-3">
            <div class="d-flex align-items-start p-3">
                <i class="bi bi-people text-info fs-3 me-3"></i>
                <div>
                    <h6 class="mb-1">C2C Marketplace</h6>
                    <small class="text-muted">Direct buyer-to-seller transactions</small>
                </div>
            </div>
        </div>
    </div>
</section>

<!-- CTA -->
<c:if test="${sessionScope.user == null}">
    <section class="text-center bg-light rounded-4 p-5 mb-4">
        <h3>Ready to start bidding?</h3>
        <p class="text-muted mb-3">Join thousands of buyers and sellers on AuctionHub.</p>
        <a href="${pageContext.request.contextPath}/auth/register" class="btn btn-primary btn-lg">
            <i class="bi bi-person-plus"></i> Create Free Account
        </a>
    </section>
</c:if>

<jsp:include page="/common/footer.jsp"/>
