<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<header class="home-header sticky-top">
    <nav class="navbar navbar-expand-lg navbar-light home-nav">
        <div class="container">
            <a class="navbar-brand home-brand d-flex align-items-center gap-1" href="${ctx}/">
                <i class="bi bi-hammer" aria-hidden="true"></i>
                AuctionHub
            </a>
            <button class="navbar-toggler border-0" type="button" data-bs-toggle="collapse"
                    data-bs-target="#homeNavMain" aria-controls="homeNavMain" aria-expanded="false"
                    aria-label="Toggle navigation">
                <span class="navbar-toggler-icon"></span>
            </button>
            <div class="collapse navbar-collapse" id="homeNavMain">
                <ul class="navbar-nav mx-lg-auto my-2 my-lg-0 align-items-lg-center flex-wrap gap-lg-1">
                    <li class="nav-item">
                        <a class="nav-link" href="${ctx}/#categories">Explore</a>
                    </li>
                    <li class="nav-item">
                        <a class="nav-link" href="${ctx}/register">Sell Items</a>
                    </li>
                    <li class="nav-item">
                        <a class="nav-link" href="#help">Help Service</a>
                    </li>
                    <li class="nav-item">
                        <c:choose>
                            <c:when test="${not empty sessionScope.userId}">
                                <a class="nav-link" href="${ctx}/protected/account">Bidding history</a>
                            </c:when>
                            <c:otherwise>
                                <a class="nav-link" href="${ctx}/login">Bidding history</a>
                            </c:otherwise>
                        </c:choose>
                    </li>
                </ul>
                <form id="homeSearchForm" class="d-flex flex-column flex-lg-row align-items-stretch align-items-lg-center gap-2 ms-lg-2"
                      action="${ctx}/" method="get" role="search">
                    <div class="home-search-wrap">
                        <div class="input-group home-search-group">
                        <span class="input-group-text bg-white border-end-0" aria-hidden="true"><i
                                class="bi bi-search"></i></span>
                        <input type="search" name="q" id="homeSearchInput" class="form-control border-start-0"
                               placeholder="Search" value="<c:out value='${param.q}'/>"
                               autocomplete="off" aria-describedby="homeSearchFeedback" maxlength="200">
                        </div>
                        <div class="invalid-feedback" id="homeSearchFeedback"></div>
                    </div>
                </form>
                <div class="d-flex align-items-center ms-lg-3 mt-2 mt-lg-0 gap-2">
                    <c:choose>
                        <c:when test="${not empty sessionScope.userId}">
                            <span class="small text-muted d-none d-lg-inline">Hello, <strong>${sessionScope.maskedUsername}</strong></span>
                            <a class="btn home-btn-signin btn-sm" href="${ctx}/protected/account">Account</a>
                            <c:if test="${sessionScope.userRole == 'ADMIN'}">
                                <a class="btn btn-outline-primary btn-sm rounded-pill" href="${ctx}/admin/dashboard">Admin</a>
                            </c:if>
                            <form action="${ctx}/logout" method="post" class="d-inline mb-0">
                                <button type="submit" class="btn btn-outline-secondary btn-sm rounded-pill">Log out</button>
                            </form>
                        </c:when>
                        <c:otherwise>
                            <a class="btn home-btn-signin" href="${ctx}/login">Sign in</a>
                        </c:otherwise>
                    </c:choose>
                </div>
                <div class="home-category-filters w-100 mt-2 ms-lg-3" aria-label="Category filters">
                    <span class="small text-muted align-self-center me-1">Filter:</span>
                    <button type="button" class="btn btn-outline-secondary btn-sm active" data-home-category="all">All</button>
                    <button type="button" class="btn btn-outline-secondary btn-sm" data-home-category="electronics">Electronics</button>
                    <button type="button" class="btn btn-outline-secondary btn-sm" data-home-category="fashion">Fashion</button>
                    <button type="button" class="btn btn-outline-secondary btn-sm" data-home-category="watches">Watches</button>
                    <button type="button" class="btn btn-outline-secondary btn-sm" data-home-category="property">Property</button>
                    <button type="button" class="btn btn-outline-secondary btn-sm" data-home-category="automotive">Cars</button>
            </div>
        </div>
    </nav>
</header>
