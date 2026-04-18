<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="${auction.productName} - AuctionHub"/>
</jsp:include>

<nav aria-label="breadcrumb" class="mb-3">
    <ol class="breadcrumb">
        <li class="breadcrumb-item"><a href="${pageContext.request.contextPath}/">Home</a></li>
        <li class="breadcrumb-item"><a href="${pageContext.request.contextPath}/product/list">Auctions</a></li>
        <li class="breadcrumb-item active">${auction.productName}</li>
    </ol>
</nav>

<div class="row g-4">
    <!-- Product Image & Info -->
    <div class="col-lg-7">
        <div class="card border-0 shadow-sm" style="border-radius:16px; overflow:hidden;">
            <c:choose>
                <c:when test="${not empty auction.productImageUrl}">
                    <img src="${auction.productImageUrl}" class="card-img-top" alt="${auction.productName}"
                         style="max-height:400px; object-fit:cover;">
                </c:when>
                <c:otherwise>
                    <div class="img-placeholder" style="height:300px;">
                        <i class="bi bi-image"></i>
                    </div>
                </c:otherwise>
            </c:choose>
            <div class="card-body">
                <div class="d-flex justify-content-between align-items-start mb-2">
                    <h2 class="mb-0">${auction.productName}</h2>
                    <c:choose>
                        <c:when test="${auction.status == 'ACTIVE'}">
                            <span class="badge bg-success fs-6">Active</span>
                        </c:when>
                        <c:when test="${auction.status == 'ENDED'}">
                            <span class="badge bg-secondary fs-6">Ended</span>
                        </c:when>
                        <c:otherwise>
                            <span class="badge bg-danger fs-6">Cancelled</span>
                        </c:otherwise>
                    </c:choose>
                </div>
                <div class="mb-3">
                    <c:if test="${not empty auction.categoryName}">
                        <span class="badge bg-info">${auction.categoryName}</span>
                    </c:if>
                    <span class="badge bg-outline-secondary border">${auction.strategy}</span>
                </div>
                <p class="text-muted">${auction.productDescription}</p>
                <div class="row text-muted small">
                    <div class="col-6">
                        <i class="bi bi-person"></i> Seller: <strong>${auction.sellerUsername}</strong>
                    </div>
                    <div class="col-6">
                        <i class="bi bi-calendar"></i> Listed: <fmt:formatDate value="${auction.createdAt}" pattern="MMM dd, yyyy"/>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <!-- Bid Section -->
    <div class="col-lg-5">
        <div class="bid-section mb-4">
            <div class="text-center mb-3">
                <small class="text-muted text-uppercase">Current Price</small>
                <div class="current-price">
                    $<fmt:formatNumber value="${auction.currentPrice}" pattern="#,##0.00"/>
                </div>
                <small class="text-muted">
                    Starting price: $<fmt:formatNumber value="${auction.startPrice}" pattern="#,##0.00"/>
                    &middot; Min increment: $<fmt:formatNumber value="${auction.bidIncrement}" pattern="#,##0.00"/>
                </small>
            </div>

            <div class="text-center mb-3">
                <small class="text-muted">Time Remaining</small>
                <div class="countdown fs-4" data-end-time="${auction.endTime}">Loading...</div>
                <small class="text-muted">
                    Ends: <fmt:formatDate value="${auction.endTime}" pattern="MMM dd, yyyy HH:mm"/>
                </small>
            </div>

            <c:if test="${not empty error}">
                <div class="alert alert-danger">${error}</div>
            </c:if>
            <c:if test="${not empty success}">
                <div class="alert alert-success">${success}</div>
            </c:if>

            <c:if test="${auction.status == 'ACTIVE'}">
                <c:choose>
                    <c:when test="${sessionScope.user != null && sessionScope.user.buyer}">
                        <form method="post" action="${pageContext.request.contextPath}/auction/bid" class="mt-3">
                            <input type="hidden" name="auctionId" value="${auction.id}">
                            <div class="input-group input-group-lg">
                                <span class="input-group-text">$</span>
                                <input type="number" class="form-control" name="amount"
                                       step="0.01"
                                       min="${auction.currentPrice.doubleValue() + auction.bidIncrement.doubleValue()}"
                                       value="${auction.currentPrice.doubleValue() + auction.bidIncrement.doubleValue()}"
                                       required>
                                <button type="submit" class="btn btn-primary">
                                    <i class="bi bi-hammer"></i> Place Bid
                                </button>
                            </div>
                        </form>
                    </c:when>
                    <c:when test="${sessionScope.user == null}">
                        <div class="text-center mt-3">
                            <a href="${pageContext.request.contextPath}/auth/login" class="btn btn-primary btn-lg w-100">
                                <i class="bi bi-box-arrow-in-right"></i> Login to Bid
                            </a>
                        </div>
                    </c:when>
                    <c:otherwise>
                        <div class="alert alert-info mt-3">
                            <i class="bi bi-info-circle"></i> Only buyers can place bids.
                        </div>
                    </c:otherwise>
                </c:choose>
            </c:if>

            <c:if test="${auction.status == 'ENDED' && not empty auction.winnerUsername}">
                <div class="alert alert-success mt-3">
                    <i class="bi bi-trophy"></i> Winner: <strong>${auction.winnerUsername}</strong>
                </div>
            </c:if>
        </div>

        <!-- Bid History -->
        <div class="card border-0 shadow-sm" style="border-radius:12px;">
            <div class="card-header bg-white">
                <h5 class="mb-0"><i class="bi bi-clock-history"></i> Bid History (${bids.size()})</h5>
            </div>
            <div class="card-body p-0">
                <c:choose>
                    <c:when test="${not empty bids}">
                        <div class="table-responsive">
                            <table class="table table-hover mb-0">
                                <thead class="table-light">
                                    <tr>
                                        <th>Bidder</th>
                                        <th class="text-end">Amount</th>
                                        <th>Time</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <c:forEach var="bid" items="${bids}" varStatus="s">
                                        <tr class="${s.first ? 'table-success' : ''}">
                                            <td>
                                                <i class="bi bi-person"></i> ${bid.buyerUsername}
                                                <c:if test="${s.first}">
                                                    <span class="badge bg-success">Highest</span>
                                                </c:if>
                                            </td>
                                            <td class="text-end fw-bold">
                                                $<fmt:formatNumber value="${bid.amount}" pattern="#,##0.00"/>
                                            </td>
                                            <td><fmt:formatDate value="${bid.bidTime}" pattern="MMM dd, HH:mm:ss"/></td>
                                        </tr>
                                    </c:forEach>
                                </tbody>
                            </table>
                        </div>
                    </c:when>
                    <c:otherwise>
                        <div class="text-center py-4 text-muted">
                            <i class="bi bi-chat-dots" style="font-size:2rem;"></i>
                            <p class="mt-2">No bids yet. Be the first!</p>
                        </div>
                    </c:otherwise>
                </c:choose>
            </div>
        </div>
    </div>
</div>

<jsp:include page="/common/footer.jsp"/>
