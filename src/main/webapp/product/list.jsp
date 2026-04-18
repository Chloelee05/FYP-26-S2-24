<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="Browse Auctions - AuctionHub"/>
</jsp:include>

<div class="d-flex justify-content-between align-items-center mb-4">
    <div>
        <h2>
            <c:choose>
                <c:when test="${not empty searchQuery}">
                    <i class="bi bi-search"></i> Results for "${searchQuery}"
                </c:when>
                <c:otherwise>
                    <i class="bi bi-grid"></i> Active Auctions
                </c:otherwise>
            </c:choose>
        </h2>
        <p class="text-muted mb-0">
            <c:choose>
                <c:when test="${not empty auctions}">
                    ${auctions.size()} auction(s) found
                </c:when>
                <c:otherwise>
                    No auctions found
                </c:otherwise>
            </c:choose>
        </p>
    </div>
    <c:if test="${sessionScope.user != null && sessionScope.user.seller}">
        <a href="${pageContext.request.contextPath}/product/create" class="btn btn-primary">
            <i class="bi bi-plus-circle"></i> Create Auction
        </a>
    </c:if>
</div>

<c:if test="${empty auctions}">
    <div class="text-center py-5">
        <i class="bi bi-inbox text-muted" style="font-size: 4rem;"></i>
        <h4 class="text-muted mt-3">No auctions available</h4>
        <p class="text-muted">Check back later or create your own auction.</p>
    </div>
</c:if>

<div class="row row-cols-1 row-cols-md-2 row-cols-lg-3 g-4">
    <c:forEach var="auction" items="${auctions}">
        <div class="col">
            <div class="card auction-card shadow-sm h-100">
                <c:choose>
                    <c:when test="${not empty auction.productImageUrl}">
                        <img src="${auction.productImageUrl}" class="card-img-top" alt="${auction.productName}">
                    </c:when>
                    <c:otherwise>
                        <div class="img-placeholder">
                            <i class="bi bi-image"></i>
                        </div>
                    </c:otherwise>
                </c:choose>
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-start mb-2">
                        <h5 class="card-title mb-0">${auction.productName}</h5>
                        <span class="badge bg-success">${auction.status}</span>
                    </div>
                    <c:if test="${not empty auction.categoryName}">
                        <span class="badge bg-secondary mb-2">${auction.categoryName}</span>
                    </c:if>
                    <p class="card-text text-muted small">
                        ${auction.productDescription != null && auction.productDescription.length() > 80
                          ? auction.productDescription.substring(0, 80).concat('...')
                          : auction.productDescription}
                    </p>
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <small class="text-muted">Current Bid</small>
                            <div class="fw-bold text-primary fs-5">
                                $<fmt:formatNumber value="${auction.currentPrice}" pattern="#,##0.00"/>
                            </div>
                        </div>
                        <div class="text-end">
                            <small class="text-muted">Ends in</small>
                            <div class="countdown" data-end-time="${auction.endTime}">Loading...</div>
                        </div>
                    </div>
                </div>
                <div class="card-footer bg-white border-top-0 d-flex justify-content-between align-items-center">
                    <small class="text-muted">
                        <i class="bi bi-person"></i> ${auction.sellerUsername}
                        &middot; ${auction.bidCount} bid(s)
                    </small>
                    <a href="${pageContext.request.contextPath}/product/detail?id=${auction.id}"
                       class="btn btn-sm btn-outline-primary">View</a>
                </div>
            </div>
        </div>
    </c:forEach>
</div>

<jsp:include page="/common/footer.jsp"/>
