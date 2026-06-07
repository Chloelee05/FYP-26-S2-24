<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>
        <c:choose>
            <c:when test="${not empty query}">Search: <c:out value="${query}"/> — AuctionHub</c:when>
            <c:otherwise>Search — AuctionHub</c:otherwise>
        </c:choose>
    </title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/home.css">
</head>
<body>

<%@ include file="/WEB-INF/includes/home-navbar.jsp" %>

<main class="container py-4">

    <%-- ===== Input validation error (SCRUM-294) ===== --%>
    <c:if test="${not empty searchError}">
        <div class="alert alert-danger d-flex align-items-center gap-2" role="alert">
            <i class="bi bi-exclamation-triangle-fill"></i>
            <c:out value="${searchError}"/>
        </div>
    </c:if>

    <c:if test="${empty searchError}">

        <%-- ===== Header row ===== --%>
        <div class="d-flex flex-wrap align-items-baseline justify-content-between gap-2 mb-3">
            <h1 class="h4 mb-0">
                Results for &ldquo;<c:out value="${query}"/>&rdquo;
            </h1>
            <span class="text-muted small">
                <c:choose>
                    <c:when test="${total == 1}">1 listing found</c:when>
                    <c:otherwise><c:out value="${total}"/> listings found</c:otherwise>
                </c:choose>
            </span>
        </div>

        <%-- ===== Active filter badges (SCRUM-59) ===== --%>
        <c:set var="hasActiveFilter"
               value="${not empty filterMinPrice or not empty filterMaxPrice
                        or not empty filterConditionId or not empty filterLocation
                        or not empty filterEndWithin}"/>
        <c:if test="${hasActiveFilter}">
            <div class="d-flex flex-wrap gap-2 mb-3 align-items-center">
                <span class="text-muted small fw-semibold">Active filters:</span>
                <c:if test="${not empty filterMinPrice or not empty filterMaxPrice}">
                    <span class="badge rounded-pill text-bg-primary">
                        <i class="bi bi-cash me-1"></i>
                        <c:choose>
                            <c:when test="${not empty filterMinPrice and not empty filterMaxPrice}">
                                $<c:out value="${filterMinPrice}"/> – $<c:out value="${filterMaxPrice}"/>
                            </c:when>
                            <c:when test="${not empty filterMinPrice}">
                                Min $<c:out value="${filterMinPrice}"/>
                            </c:when>
                            <c:otherwise>
                                Max $<c:out value="${filterMaxPrice}"/>
                            </c:otherwise>
                        </c:choose>
                    </span>
                </c:if>
                <c:if test="${not empty filterConditionId}">
                    <span class="badge rounded-pill text-bg-success">
                        <i class="bi bi-tag me-1"></i>
                        <c:choose>
                            <c:when test="${filterConditionId == 1}">Brand New</c:when>
                            <c:when test="${filterConditionId == 2}">Slightly Used</c:when>
                            <c:when test="${filterConditionId == 3}">Used</c:when>
                            <c:when test="${filterConditionId == 4}">Damaged</c:when>
                        </c:choose>
                    </span>
                </c:if>
                <c:if test="${not empty filterLocation}">
                    <span class="badge rounded-pill text-bg-secondary">
                        <i class="bi bi-geo-alt me-1"></i><c:out value="${filterLocation}"/>
                    </span>
                </c:if>
                <c:if test="${not empty filterEndWithin}">
                    <span class="badge rounded-pill text-bg-warning text-dark">
                        <i class="bi bi-clock me-1"></i>Ending within <c:out value="${filterEndWithin}"/>h
                    </span>
                </c:if>
                <a href="${pageContext.request.contextPath}/search?q=<c:out value="${query}"/>"
                   class="btn btn-sm btn-outline-secondary">
                    <i class="bi bi-x-circle me-1"></i>Clear filters
                </a>
            </div>
        </c:if>

        <div class="row g-4">

            <%-- ===== SCRUM-59: Filter sidebar ===== --%>
            <div class="col-12 col-md-3">
                <div class="card shadow-sm">
                    <div class="card-header d-flex align-items-center gap-2 fw-semibold">
                        <i class="bi bi-funnel"></i> Filters
                    </div>
                    <div class="card-body">
                        <form method="get" action="${pageContext.request.contextPath}/search">
                            <input type="hidden" name="q" value="<c:out value="${query}"/>">
                            <c:if test="${not empty categorySlug}">
                                <input type="hidden" name="category" value="<c:out value="${categorySlug}"/>">
                            </c:if>

                            <%-- Price range (SCRUM-345: non-negative, validated server-side) --%>
                            <div class="mb-3">
                                <label class="form-label fw-semibold small text-uppercase text-muted">Price Range</label>
                                <div class="input-group input-group-sm mb-1">
                                    <span class="input-group-text">$</span>
                                    <input type="number" class="form-control" name="minPrice"
                                           placeholder="Min" min="0" step="0.01"
                                           value="<c:out value="${filterMinPrice}"/>">
                                </div>
                                <div class="input-group input-group-sm">
                                    <span class="input-group-text">$</span>
                                    <input type="number" class="form-control" name="maxPrice"
                                           placeholder="Max" min="0" step="0.01"
                                           value="<c:out value="${filterMaxPrice}"/>">
                                </div>
                            </div>

                            <%-- Item condition (SCRUM-345: enum whitelist validated server-side) --%>
                            <div class="mb-3">
                                <label for="conditionSelect" class="form-label fw-semibold small text-uppercase text-muted">
                                    Condition
                                </label>
                                <select class="form-select form-select-sm" id="conditionSelect" name="condition">
                                    <option value="">Any condition</option>
                                    <option value="BRAND_NEW"    ${filterConditionId == 1 ? 'selected' : ''}>Brand New</option>
                                    <option value="SLIGHTLY_USED" ${filterConditionId == 2 ? 'selected' : ''}>Slightly Used</option>
                                    <option value="USED"          ${filterConditionId == 3 ? 'selected' : ''}>Used</option>
                                    <option value="DAMAGED"       ${filterConditionId == 4 ? 'selected' : ''}>Damaged</option>
                                </select>
                            </div>

                            <%-- Location (free-text, max 100 chars, server-side length check) --%>
                            <div class="mb-3">
                                <label for="locationInput" class="form-label fw-semibold small text-uppercase text-muted">
                                    Location
                                </label>
                                <input type="text" class="form-control form-control-sm" id="locationInput"
                                       name="location" placeholder="e.g. Singapore" maxlength="100"
                                       value="<c:out value="${filterLocation}"/>">
                            </div>

                            <%-- Ending soon --%>
                            <div class="mb-4">
                                <label for="endWithinSelect" class="form-label fw-semibold small text-uppercase text-muted">
                                    Ending
                                </label>
                                <select class="form-select form-select-sm" id="endWithinSelect" name="endWithin">
                                    <option value="">Any time</option>
                                    <option value="24"  ${filterEndWithin == 24  ? 'selected' : ''}>Within 24 hours</option>
                                    <option value="48"  ${filterEndWithin == 48  ? 'selected' : ''}>Within 48 hours</option>
                                    <option value="168" ${filterEndWithin == 168 ? 'selected' : ''}>Within 7 days</option>
                                </select>
                            </div>

                            <div class="d-grid gap-2">
                                <button type="submit" class="btn btn-primary btn-sm">
                                    <i class="bi bi-search me-1"></i>Apply Filters
                                </button>
                                <a href="${pageContext.request.contextPath}/search?q=<c:out value="${query}"/>"
                                   class="btn btn-outline-secondary btn-sm">
                                    Reset
                                </a>
                            </div>
                        </form>
                    </div>
                </div>
            </div>

            <%-- ===== Results column ===== --%>
            <div class="col-12 col-md-9">

                <%-- SCRUM-60: Sort bar --%>
                <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
                    <span class="text-muted small fw-semibold">
                        <i class="bi bi-sort-down me-1"></i>Sort by
                    </span>
                    <form method="get" action="${pageContext.request.contextPath}/search"
                          class="d-flex align-items-center gap-2" id="sortForm">
                        <input type="hidden" name="q" value="<c:out value="${query}"/>">
                        <c:if test="${not empty categorySlug}">
                            <input type="hidden" name="category" value="<c:out value="${categorySlug}"/>">
                        </c:if>
                        <c:if test="${not empty filterMinPrice}">
                            <input type="hidden" name="minPrice" value="<c:out value="${filterMinPrice}"/>">
                        </c:if>
                        <c:if test="${not empty filterMaxPrice}">
                            <input type="hidden" name="maxPrice" value="<c:out value="${filterMaxPrice}"/>">
                        </c:if>
                        <c:if test="${not empty filterConditionId}">
                            <c:choose>
                                <c:when test="${filterConditionId == 1}"><input type="hidden" name="condition" value="BRAND_NEW"></c:when>
                                <c:when test="${filterConditionId == 2}"><input type="hidden" name="condition" value="SLIGHTLY_USED"></c:when>
                                <c:when test="${filterConditionId == 3}"><input type="hidden" name="condition" value="USED"></c:when>
                                <c:when test="${filterConditionId == 4}"><input type="hidden" name="condition" value="DAMAGED"></c:when>
                            </c:choose>
                        </c:if>
                        <c:if test="${not empty filterLocation}">
                            <input type="hidden" name="location" value="<c:out value="${filterLocation}"/>">
                        </c:if>
                        <c:if test="${not empty filterEndWithin}">
                            <input type="hidden" name="endWithin" value="<c:out value="${filterEndWithin}"/>">
                        </c:if>
                        <select class="form-select form-select-sm" name="sortBy" style="width:auto"
                                onchange="this.form.submit()" aria-label="Sort results">
                            <option value="newest"      ${sortBy == 'newest' or empty sortBy ? 'selected' : ''}>Newly listed</option>
                            <option value="endingSoon"  ${sortBy == 'endingSoon' ? 'selected' : ''}>Ending soonest</option>
                            <option value="priceLow"    ${sortBy == 'priceLow' ? 'selected' : ''}>Price: low to high</option>
                            <option value="priceHigh"   ${sortBy == 'priceHigh' ? 'selected' : ''}>Price: high to low</option>
                        </select>
                    </form>
                </div>

                <%-- SCRUM-259: empty results UX --%>
                <c:if test="${searchEmpty}">
                    <div class="text-center py-5">
                        <i class="bi bi-search display-3 text-muted mb-3 d-block"></i>
                        <h2 class="h5">No results found for &ldquo;<c:out value="${query}"/>&rdquo;</h2>
                        <p class="text-muted">
                            <c:choose>
                                <c:when test="${hasActiveFilter}">
                                    Try adjusting your filters or clearing them to see more results.
                                </c:when>
                                <c:otherwise>
                                    Try different keywords, check spelling, or browse all categories.
                                </c:otherwise>
                            </c:choose>
                        </p>
                        <a href="${pageContext.request.contextPath}/" class="btn btn-primary mt-2">
                            <i class="bi bi-house me-1"></i>Back to Home
                        </a>
                    </div>
                </c:if>

                <%-- Results grid --%>
                <c:if test="${not searchEmpty}">
                    <div class="row row-cols-1 row-cols-sm-2 row-cols-xl-3 g-4 mb-4">
                        <c:forEach var="item" items="${results}">
                            <div class="col">
                                <div class="card h-100 shadow-sm">
                                    <c:choose>
                                        <c:when test="${not empty item.thumbnailUrl}">
                                            <img src="<c:out value="${item.thumbnailUrl}"/>"
                                                 class="card-img-top object-fit-cover"
                                                 style="height:180px"
                                                 alt="<c:out value="${item.title}"/>">
                                        </c:when>
                                        <c:otherwise>
                                            <div class="card-img-top bg-light d-flex align-items-center justify-content-center"
                                                 style="height:180px">
                                                <i class="bi bi-image text-muted fs-1"></i>
                                            </div>
                                        </c:otherwise>
                                    </c:choose>
                                    <div class="card-body d-flex flex-column">
                                        <span class="badge text-bg-secondary mb-1 align-self-start small">
                                            <c:out value="${item.category}"/>
                                        </span>
                                        <h5 class="card-title fs-6 fw-semibold mb-1">
                                            <c:out value="${item.title}"/>
                                        </h5>
                                        <p class="card-text text-muted small mb-auto">
                                            Seller: <c:out value="${item.sellerUsername}"/>
                                        </p>
                                    </div>
                                    <div class="card-footer d-flex justify-content-between align-items-center">
                                        <div>
                                            <div class="small text-muted">Current bid</div>
                                            <strong class="text-primary">
                                                <fmt:formatNumber value="${item.currentPrice}" type="currency" currencyCode="USD"/>
                                            </strong>
                                        </div>
                                        <a href="${pageContext.request.contextPath}/auction/${item.auctionId}"
                                           class="btn btn-outline-primary btn-sm">
                                            View
                                        </a>
                                    </div>
                                </div>
                            </div>
                        </c:forEach>
                    </div>

                    <%-- Pagination — preserves all filter params (SCRUM-59) --%>
                    <c:if test="${totalPages > 1}">
                        <%-- Build the shared query string for pagination links --%>
                        <c:set var="pagerBase" value="?q=${query}"/>
                        <c:if test="${not empty categorySlug}">
                            <c:set var="pagerBase" value="${pagerBase}&amp;category=${categorySlug}"/>
                        </c:if>
                        <c:if test="${not empty filterMinPrice}">
                            <c:set var="pagerBase" value="${pagerBase}&amp;minPrice=${filterMinPrice}"/>
                        </c:if>
                        <c:if test="${not empty filterMaxPrice}">
                            <c:set var="pagerBase" value="${pagerBase}&amp;maxPrice=${filterMaxPrice}"/>
                        </c:if>
                        <c:if test="${not empty filterConditionId}">
                            <c:choose>
                                <c:when test="${filterConditionId == 1}"><c:set var="pagerBase" value="${pagerBase}&amp;condition=BRAND_NEW"/></c:when>
                                <c:when test="${filterConditionId == 2}"><c:set var="pagerBase" value="${pagerBase}&amp;condition=SLIGHTLY_USED"/></c:when>
                                <c:when test="${filterConditionId == 3}"><c:set var="pagerBase" value="${pagerBase}&amp;condition=USED"/></c:when>
                                <c:when test="${filterConditionId == 4}"><c:set var="pagerBase" value="${pagerBase}&amp;condition=DAMAGED"/></c:when>
                            </c:choose>
                        </c:if>
                        <c:if test="${not empty filterLocation}">
                            <c:set var="pagerBase" value="${pagerBase}&amp;location=${filterLocation}"/>
                        </c:if>
                        <c:if test="${not empty filterEndWithin}">
                            <c:set var="pagerBase" value="${pagerBase}&amp;endWithin=${filterEndWithin}"/>
                        </c:if>
                        <c:if test="${not empty sortBy and sortBy ne 'newest'}">
                            <c:set var="pagerBase" value="${pagerBase}&amp;sortBy=${sortBy}"/>
                        </c:if>
                        <c:set var="pagerBase" value="${pagerBase}&amp;size=${pageSize}"/>

                        <nav aria-label="Search result pages">
                            <ul class="pagination justify-content-center flex-wrap">
                                <li class="page-item ${currentPage <= 1 ? 'disabled' : ''}">
                                    <a class="page-link"
                                       href="${pagerBase}&amp;page=${currentPage - 1}"
                                       aria-label="Previous">
                                        <i class="bi bi-chevron-left"></i>
                                    </a>
                                </li>
                                <c:forEach begin="1" end="${totalPages}" var="p">
                                    <li class="page-item ${p == currentPage ? 'active' : ''}">
                                        <a class="page-link" href="${pagerBase}&amp;page=${p}">${p}</a>
                                    </li>
                                </c:forEach>
                                <li class="page-item ${currentPage >= totalPages ? 'disabled' : ''}">
                                    <a class="page-link"
                                       href="${pagerBase}&amp;page=${currentPage + 1}"
                                       aria-label="Next">
                                        <i class="bi bi-chevron-right"></i>
                                    </a>
                                </li>
                            </ul>
                        </nav>
                    </c:if>
                </c:if>

            </div><%-- end results col --%>
        </div><%-- end row --%>
    </c:if><%-- end no searchError --%>

</main>

<%@ include file="/WEB-INF/includes/home-footer.jsp" %>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
