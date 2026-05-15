<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>User Profile — AuctionHub</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/profile.css">
</head>
<body class="bg-light">

<%@ include file="/WEB-INF/includes/navbar.jsp" %>

<c:set var="ctx" value="${pageContext.request.contextPath}"/>

<div class="container py-4 pb-5">
    <c:if test="${param.updated == '1'}">
        <div class="alert alert-success alert-dismissible fade show" role="alert">
            Your profile was updated.
            <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
        </div>
    </c:if>

    <header class="profile-page-header d-flex flex-column flex-md-row justify-content-between align-items-start align-items-md-center gap-3 pb-3 mb-4">
        <div>
            <h1 class="h3 fw-bold mb-0">User Profile</h1>
            <p class="text-muted small mb-0">How you appear to others and your activity on AuctionHub.</p>
        </div>
        <div class="d-flex flex-wrap gap-2">
            <a class="btn btn-outline-secondary btn-sm rounded-pill" href="${ctx}/protected/account"><i class="bi bi-person-lines-fill me-1"></i> Profile</a>
            <a class="btn btn-primary btn-sm rounded-pill" href="${ctx}/protected/account/edit"><i class="bi bi-gear me-1"></i> Settings</a>
        </div>
    </header>

    <div class="row g-4">
        <%-- Left column: masked public card + ratings --%>
        <div class="col-lg-4">
            <div class="profile-card p-4 mb-4">
                <div class="text-center mb-3">
                    <c:choose>
                        <c:when test="${not empty profileImageUrl}">
                            <img src="<c:out value="${profileImageUrl}"/>" alt="" class="profile-avatar-lg mx-auto d-block">
                        </c:when>
                        <c:otherwise>
                            <div class="profile-avatar-placeholder mx-auto"><i class="bi bi-person-fill"></i></div>
                        </c:otherwise>
                    </c:choose>
                </div>
                <h2 class="h5 fw-semibold text-center mb-1"><c:out value="${profileUsername}"/></h2>
                <p class="text-muted small text-center mb-3">
                    <c:choose>
                        <c:when test="${not empty memberSinceFormatted}">Member since <c:out value="${memberSinceFormatted}"/></c:when>
                        <c:otherwise>Member</c:otherwise>
                    </c:choose>
                </p>
                <p class="small text-uppercase text-muted fw-semibold mb-2">Contact (masked — public)</p>
                <ul class="list-unstyled mb-0 profile-contact-list">
                    <li class="profile-contact-item mb-2 d-flex gap-2">
                        <i class="bi bi-envelope text-primary mt-1"></i>
                        <span><c:out value="${publicMaskedEmail}"/></span>
                    </li>
                    <li class="profile-contact-item mb-2 d-flex gap-2">
                        <i class="bi bi-telephone text-primary mt-1"></i>
                        <span>
                            <c:choose>
                                <c:when test="${empty publicMaskedPhone}">Not shown</c:when>
                                <c:otherwise><c:out value="${publicMaskedPhone}"/></c:otherwise>
                            </c:choose>
                        </span>
                    </li>
                    <li class="profile-contact-item d-flex gap-2">
                        <i class="bi bi-geo-alt text-primary mt-1"></i>
                        <span>
                            <c:choose>
                                <c:when test="${empty publicMaskedAddress}">Not shown</c:when>
                                <c:otherwise><c:out value="${publicMaskedAddress}"/></c:otherwise>
                            </c:choose>
                        </span>
                    </li>
                </ul>
                <a href="${ctx}/protected/account/edit" class="btn btn-outline-primary w-100 rounded-pill mt-3">
                    <i class="bi bi-pencil me-1"></i> Edit profile
                </a>
            </div>

            <div class="profile-rating-card p-4 mb-4">
                <h3 class="h6 fw-semibold mb-3">Ratings</h3>
                <div class="d-flex align-items-center gap-2 mb-2">
                    <span class="display-6 fw-bold text-dark">
                        <c:choose>
                            <c:when test="${ratingSummary.reviewCount > 0}">
                                <fmt:formatNumber value="${ratingSummary.average}" maxFractionDigits="1" minFractionDigits="1"/>
                            </c:when>
                            <c:otherwise>—</c:otherwise>
                        </c:choose>
                    </span>
                    <div class="text-warning">
                        <c:forEach begin="1" end="5" var="s">
                            <i class="bi bi-star${s <= ratingStarsFilled ? '-fill' : ''}"></i>
                        </c:forEach>
                    </div>
                </div>
                <p class="small text-muted mb-3">${ratingSummary.reviewCount} review<c:if test="${ratingSummary.reviewCount != 1}">s</c:if></p>
                <c:set var="bw0" value="${ratingSummary.barWidthsPercent[0]}"/>
                <c:set var="bw1" value="${ratingSummary.barWidthsPercent[1]}"/>
                <c:set var="bw2" value="${ratingSummary.barWidthsPercent[2]}"/>
                <c:set var="bw3" value="${ratingSummary.barWidthsPercent[3]}"/>
                <c:set var="bw4" value="${ratingSummary.barWidthsPercent[4]}"/>
                <c:set var="sc0" value="${ratingSummary.starCountsHighToLow[0]}"/>
                <c:set var="sc1" value="${ratingSummary.starCountsHighToLow[1]}"/>
                <c:set var="sc2" value="${ratingSummary.starCountsHighToLow[2]}"/>
                <c:set var="sc3" value="${ratingSummary.starCountsHighToLow[3]}"/>
                <c:set var="sc4" value="${ratingSummary.starCountsHighToLow[4]}"/>
                <div class="profile-star-row d-flex align-items-center gap-2 mb-1">
                    <span class="text-nowrap" style="width:3rem">5 ★</span>
                    <div class="progress flex-grow-1" style="height:6px;">
                        <div class="progress-bar bg-warning" style="width: ${bw0}%;"></div>
                    </div>
                    <span class="text-end text-muted" style="min-width:1.25rem">${sc0}</span>
                </div>
                <div class="profile-star-row d-flex align-items-center gap-2 mb-1">
                    <span class="text-nowrap" style="width:3rem">4 ★</span>
                    <div class="progress flex-grow-1" style="height:6px;">
                        <div class="progress-bar bg-warning" style="width: ${bw1}%;"></div>
                    </div>
                    <span class="text-end text-muted" style="min-width:1.25rem">${sc1}</span>
                </div>
                <div class="profile-star-row d-flex align-items-center gap-2 mb-1">
                    <span class="text-nowrap" style="width:3rem">3 ★</span>
                    <div class="progress flex-grow-1" style="height:6px;">
                        <div class="progress-bar bg-warning" style="width: ${bw2}%;"></div>
                    </div>
                    <span class="text-end text-muted" style="min-width:1.25rem">${sc2}</span>
                </div>
                <div class="profile-star-row d-flex align-items-center gap-2 mb-1">
                    <span class="text-nowrap" style="width:3rem">2 ★</span>
                    <div class="progress flex-grow-1" style="height:6px;">
                        <div class="progress-bar bg-warning" style="width: ${bw3}%;"></div>
                    </div>
                    <span class="text-end text-muted" style="min-width:1.25rem">${sc3}</span>
                </div>
                <div class="profile-star-row d-flex align-items-center gap-2 mb-1">
                    <span class="text-nowrap" style="width:3rem">1 ★</span>
                    <div class="progress flex-grow-1" style="height:6px;">
                        <div class="progress-bar bg-warning" style="width: ${bw4}%;"></div>
                    </div>
                    <span class="text-end text-muted" style="min-width:1.25rem">${sc4}</span>
                </div>
            </div>

            <div class="card border-0 shadow-sm mb-4">
                <div class="card-header bg-white py-2 small fw-semibold text-secondary">Your profile (private)</div>
                <div class="card-body small">
                    <dl class="row mb-0">
                        <dt class="col-4 text-muted">Email</dt>
                        <dd class="col-8 mb-2"><c:out value="${profileEmail}"/></dd>
                        <dt class="col-4 text-muted">Phone</dt>
                        <dd class="col-8 mb-2">
                            <c:choose>
                                <c:when test="${empty profilePhone}">—</c:when>
                                <c:otherwise><c:out value="${profilePhone}"/></c:otherwise>
                            </c:choose>
                        </dd>
                        <dt class="col-4 text-muted">Address</dt>
                        <dd class="col-8 mb-0">
                            <c:choose>
                                <c:when test="${empty profileAddress}">—</c:when>
                                <c:otherwise><c:out value="${profileAddress}"/></c:otherwise>
                            </c:choose>
                        </dd>
                    </dl>
                </div>
            </div>
        </div>

        <%-- Main: tabs --%>
        <div class="col-lg-8">
            <div class="profile-main-panel p-3 p-md-4">
                <ul class="nav nav-tabs mb-3" id="profileTabs" role="tablist">
                    <li class="nav-item" role="presentation">
                        <button class="nav-link active" id="tx-tab" data-bs-toggle="tab" data-bs-target="#tx-panel" type="button" role="tab" aria-controls="tx-panel" aria-selected="true">
                            Transaction History
                        </button>
                    </li>
                    <li class="nav-item" role="presentation">
                        <button class="nav-link" id="rev-tab" data-bs-toggle="tab" data-bs-target="#rev-panel" type="button" role="tab" aria-controls="rev-panel" aria-selected="false">
                            Reviews
                        </button>
                    </li>
                </ul>
                <div class="tab-content" id="profileTabsContent">
                    <div class="tab-pane fade show active" id="tx-panel" role="tabpanel" aria-labelledby="tx-tab" tabindex="0">
                        <form method="get" action="${ctx}/protected/account" class="row g-2 align-items-center mb-3">
                            <c:if test="${param.updated == '1'}">
                                <input type="hidden" name="updated" value="1"/>
                            </c:if>
                            <div class="col-auto">
                                <label class="col-form-label small text-muted" for="txFilter">Filter</label>
                            </div>
                            <div class="col-auto">
                                <select class="form-select form-select-sm" id="txFilter" name="tx" onchange="this.form.submit()" aria-label="Filter transactions">
                                    <option value="all" <c:if test="${txFilter == 'all'}">selected</c:if>>All</option>
                                    <option value="purchase" <c:if test="${txFilter == 'purchase'}">selected</c:if>>Purchases</option>
                                    <option value="sale" <c:if test="${txFilter == 'sale'}">selected</c:if>>Sales</option>
                                </select>
                            </div>
                        </form>
                        <div class="table-responsive">
                            <table class="table table-hover profile-tx-table align-middle mb-0">
                                <thead class="table-light">
                                <tr>
                                    <th scope="col">ID</th>
                                    <th scope="col">Date</th>
                                    <th scope="col">Item</th>
                                    <th scope="col">Type</th>
                                    <th scope="col" class="text-end">Amount</th>
                                    <th scope="col">Status</th>
                                </tr>
                                </thead>
                                <tbody>
                                <c:forEach var="t" items="${transactions}">
                                    <tr>
                                        <td><c:out value="${t.displayId}"/></td>
                                        <td><c:out value="${t.transactionDate}"/></td>
                                        <td><c:out value="${t.itemTitle}"/></td>
                                        <td>
                                            <c:choose>
                                                <c:when test="${t.transactionType == 'purchase'}"><span class="badge text-bg-primary">purchase</span></c:when>
                                                <c:otherwise><span class="badge text-bg-success">sale</span></c:otherwise>
                                            </c:choose>
                                        </td>
                                        <td class="text-end"><fmt:formatNumber value="${t.amount}" type="currency" currencyCode="USD"/></td>
                                        <td>
                                            <c:choose>
                                                <c:when test="${t.status == 'Completed'}"><span class="badge text-bg-success">Completed</span></c:when>
                                                <c:otherwise><span class="badge text-bg-warning text-dark">Pending</span></c:otherwise>
                                            </c:choose>
                                        </td>
                                    </tr>
                                </c:forEach>
                                <c:if test="${empty transactions}">
                                    <tr>
                                        <td colspan="6" class="text-muted text-center py-4">No transactions yet.</td>
                                    </tr>
                                </c:if>
                                </tbody>
                            </table>
                        </div>
                        <div class="row g-3 mt-3 pt-3 border-top small">
                            <div class="col-md-4">
                                <span class="text-muted">Total purchases</span>
                                <div class="fs-5 fw-semibold text-primary">${txPurchaseTotal}</div>
                            </div>
                            <div class="col-md-4">
                                <span class="text-muted">Total sales</span>
                                <div class="fs-5 fw-semibold text-success">${txSaleTotal}</div>
                            </div>
                            <div class="col-md-4">
                                <span class="text-muted">Total volume</span>
                                <div class="fs-5 fw-semibold text-purple" style="color:#6f42c1;">
                                    <fmt:formatNumber value="${txVolumeTotal}" type="currency" currencyCode="USD"/>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="tab-pane fade" id="rev-panel" role="tabpanel" aria-labelledby="rev-tab" tabindex="0">
                        <c:choose>
                            <c:when test="${empty reviewsAboutMe}">
                                <p class="text-muted mb-0">No reviews yet.</p>
                            </c:when>
                            <c:otherwise>
                                <ul class="list-group list-group-flush">
                                    <c:forEach var="r" items="${reviewsAboutMe}">
                                        <li class="list-group-item px-0 py-3">
                                            <div class="d-flex justify-content-between align-items-start gap-2">
                                                <div>
                                                    <strong><c:out value="${r.reviewerMaskedName}"/></strong>
                                                    <span class="text-warning ms-1">
                                                        <c:forEach begin="1" end="5" var="s"><i class="bi bi-star${s <= r.rating ? '-fill' : ''} small"></i></c:forEach>
                                                    </span>
                                                </div>
                                                <span class="small text-muted"><c:out value="${r.reviewDate}"/></span>
                                            </div>
                                            <c:if test="${not empty r.auctionTitle}">
                                                <div class="small text-muted mt-1">Item: <c:out value="${r.auctionTitle}"/></div>
                                            </c:if>
                                            <p class="mb-0 mt-2 small"><c:out value="${r.comment}"/></p>
                                        </li>
                                    </c:forEach>
                                </ul>
                            </c:otherwise>
                        </c:choose>
                    </div>
                </div>
            </div>

            <div class="card border-danger shadow-sm mt-4">
                <div class="card-header bg-danger text-white py-3">
                    <span class="fw-semibold">Danger zone</span>
                </div>
                <div class="card-body">
                    <p class="card-text text-muted small mb-3">
                        Permanently close your account. Your personal data will be removed or anonymised (PDPA).
                        Historical auction records may retain an anonymised user reference.
                    </p>
                    <button type="button" class="btn btn-outline-danger" data-bs-toggle="modal" data-bs-target="#deleteAccountModal">
                        Delete my account
                    </button>
                </div>
            </div>
        </div>
    </div>
</div>

<div class="modal fade" id="deleteAccountModal" tabindex="-1" aria-labelledby="deleteAccountModalLabel" aria-hidden="true">
    <div class="modal-dialog modal-dialog-centered">
        <div class="modal-content border-danger">
            <div class="modal-header">
                <h5 class="modal-title text-danger" id="deleteAccountModalLabel">Delete account?</h5>
                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>
            <div class="modal-body">
                <p class="mb-2">This cannot be undone. You will be signed out immediately.</p>
            </div>
            <div class="modal-footer flex-column align-items-stretch gap-2">
                <form method="post" action="${ctx}/protected/account/delete" class="d-grid">
                    <input type="hidden" name="confirm" value="DELETE"/>
                    <button type="submit" class="btn btn-danger">Yes, delete my account</button>
                </form>
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
            </div>
        </div>
    </div>
</div>

<%-- SCRUM-183: verified breakpoints — nav stacks &lt; lg; left column full width; table horizontal scroll; tabs full width. --%>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
