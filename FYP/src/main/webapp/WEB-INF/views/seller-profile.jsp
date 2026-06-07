<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title><c:out value="${profile.username}"/> — Seller Profile — AuctionHub</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/home.css">
</head>
<body>

<%@ include file="/WEB-INF/includes/home-navbar.jsp" %>

<main class="container py-4">

    <%-- Profile header --%>
    <div class="card shadow-sm mb-4">
        <div class="card-body">
            <div class="d-flex flex-wrap align-items-center gap-3">
                <c:choose>
                    <c:when test="${not empty profile.profileImageUrl}">
                        <img src="<c:out value="${profile.profileImageUrl}"/>"
                             alt="Seller avatar" class="rounded-circle"
                             style="width:72px;height:72px;object-fit:cover;">
                    </c:when>
                    <c:otherwise>
                        <div class="rounded-circle bg-primary text-white d-flex align-items-center justify-content-center"
                             style="width:72px;height:72px;font-size:2rem;">
                            <i class="bi bi-person-fill"></i>
                        </div>
                    </c:otherwise>
                </c:choose>
                <div class="flex-grow-1">
                    <h1 class="h3 fw-bold mb-1"><c:out value="${profile.username}"/></h1>
                    <p class="text-muted small mb-1">
                        <i class="bi bi-envelope me-1"></i>
                        <c:out value="${profile.maskedEmail}"/>
                    </p>
                    <p class="text-muted small mb-0">
                        <i class="bi bi-calendar3 me-1"></i>
                        Member since
                        <fmt:formatDate value="${profile.memberSinceDate}" pattern="MMM yyyy"/>
                    </p>
                </div>
                <div class="text-end">
                    <div class="display-6 fw-bold text-warning mb-0">
                        <c:choose>
                            <c:when test="${reviewCount == 0}">—</c:when>
                            <c:otherwise>
                                <fmt:formatNumber value="${avgRating}" minFractionDigits="1" maxFractionDigits="1"/>
                                <i class="bi bi-star-fill fs-5"></i>
                            </c:otherwise>
                        </c:choose>
                    </div>
                    <div class="small text-muted">
                        <c:choose>
                            <c:when test="${reviewCount == 1}">1 review</c:when>
                            <c:otherwise><c:out value="${reviewCount}"/> reviews</c:otherwise>
                        </c:choose>
                    </div>
                </div>
            </div>
        </div>
        <div class="card-footer bg-light d-flex gap-4 flex-wrap small">
            <span>
                <i class="bi bi-shop me-1 text-primary"></i>
                <strong><c:out value="${profile.activeListingCount}"/></strong>
                active listing<c:if test="${profile.activeListingCount != 1}">s</c:if>
            </span>
        </div>
    </div>

    <%-- Reviews --%>
    <h2 class="h5 fw-semibold mb-3">
        <i class="bi bi-chat-square-heart me-2"></i>Review History
    </h2>

    <c:if test="${reviewsEmpty}">
        <div class="text-center py-5 text-muted">
            <i class="bi bi-star display-4 d-block mb-2"></i>
            <p class="mb-0">No reviews yet for this seller.</p>
        </div>
    </c:if>

    <c:if test="${not reviewsEmpty}">
        <div class="list-group list-group-flush mb-4">
            <c:forEach var="r" items="${reviews}">
                <div class="list-group-item px-0 py-3">
                    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-1">
                        <div>
                            <span class="text-warning">
                                <c:forEach begin="1" end="${r.rating}">
                                    <i class="bi bi-star-fill"></i>
                                </c:forEach>
                                <c:forEach begin="${r.rating + 1}" end="5">
                                    <i class="bi bi-star"></i>
                                </c:forEach>
                            </span>
                            <span class="badge text-bg-light border ms-2">
                                <c:out value="${r.reviewerMaskedName}"/>
                            </span>
                        </div>
                        <small class="text-muted">
                            <fmt:formatDate value="${r.reviewDateUtil}" pattern="dd MMM yyyy"/>
                        </small>
                    </div>
                    <c:if test="${not empty r.auctionTitle}">
                        <div class="small text-muted mb-1">
                            Re: <c:out value="${r.auctionTitle}"/>
                        </div>
                    </c:if>
                    <p class="mb-0 small"><c:out value="${r.comment}"/></p>
                </div>
            </c:forEach>
        </div>

        <%-- Review pagination --%>
        <c:if test="${reviewTotalPages > 1}">
            <nav aria-label="Review pages">
                <ul class="pagination justify-content-center flex-wrap">
                    <li class="page-item ${reviewPage <= 1 ? 'disabled' : ''}">
                        <a class="page-link"
                           href="?page=${reviewPage - 1}&amp;size=${reviewPageSize}">
                            <i class="bi bi-chevron-left"></i>
                        </a>
                    </li>
                    <c:forEach begin="1" end="${reviewTotalPages}" var="p">
                        <li class="page-item ${p == reviewPage ? 'active' : ''}">
                            <a class="page-link" href="?page=${p}&amp;size=${reviewPageSize}">${p}</a>
                        </li>
                    </c:forEach>
                    <li class="page-item ${reviewPage >= reviewTotalPages ? 'disabled' : ''}">
                        <a class="page-link"
                           href="?page=${reviewPage + 1}&amp;size=${reviewPageSize}">
                            <i class="bi bi-chevron-right"></i>
                        </a>
                    </li>
                </ul>
            </nav>
        </c:if>
    </c:if>

</main>

<%@ include file="/WEB-INF/includes/home-footer.jsp" %>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
