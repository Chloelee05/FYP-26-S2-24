<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="Create Auction - AuctionHub"/>
</jsp:include>

<div class="row justify-content-center">
    <div class="col-lg-8">
        <div class="card border-0 shadow-sm" style="border-radius:16px;">
            <div class="card-body p-4">
                <h3 class="mb-4"><i class="bi bi-plus-circle text-primary"></i> Create New Auction</h3>

                <c:if test="${not empty error}">
                    <div class="alert alert-danger">${error}</div>
                </c:if>

                <form method="post" action="${pageContext.request.contextPath}/product/create">
                    <h5 class="text-muted mb-3">Product Details</h5>

                    <div class="mb-3">
                        <label for="name" class="form-label">Product Name <span class="text-danger">*</span></label>
                        <input type="text" class="form-control" id="name" name="name"
                               placeholder="e.g. Vintage Rolex Watch" required>
                    </div>

                    <div class="mb-3">
                        <label for="description" class="form-label">Description</label>
                        <textarea class="form-control" id="description" name="description"
                                  rows="4" placeholder="Describe your item in detail..."></textarea>
                    </div>

                    <div class="row mb-3">
                        <div class="col-md-6">
                            <label for="categoryId" class="form-label">Category</label>
                            <select class="form-select" id="categoryId" name="categoryId">
                                <c:forEach var="cat" items="${categories}">
                                    <option value="${cat.id}">${cat.name}</option>
                                </c:forEach>
                            </select>
                        </div>
                        <div class="col-md-6">
                            <label for="imageUrl" class="form-label">Image URL</label>
                            <input type="url" class="form-control" id="imageUrl" name="imageUrl"
                                   placeholder="https://example.com/image.jpg">
                        </div>
                    </div>

                    <hr class="my-4">
                    <h5 class="text-muted mb-3">Auction Settings</h5>

                    <div class="row mb-3">
                        <div class="col-md-4">
                            <label for="startPrice" class="form-label">Starting Price ($) <span class="text-danger">*</span></label>
                            <input type="number" class="form-control" id="startPrice" name="startPrice"
                                   step="0.01" min="0.01" placeholder="10.00" required>
                        </div>
                        <div class="col-md-4">
                            <label for="bidIncrement" class="form-label">Bid Increment ($)</label>
                            <input type="number" class="form-control" id="bidIncrement" name="bidIncrement"
                                   step="0.01" min="0.01" value="1.00">
                        </div>
                        <div class="col-md-4">
                            <label for="duration" class="form-label">Duration (hours)</label>
                            <select class="form-select" id="duration" name="duration">
                                <option value="24">1 Day</option>
                                <option value="72">3 Days</option>
                                <option value="168" selected>7 Days</option>
                                <option value="336">14 Days</option>
                                <option value="720">30 Days</option>
                            </select>
                        </div>
                    </div>

                    <div class="mb-4">
                        <label class="form-label">Auction Strategy</label>
                        <div class="row g-2">
                            <div class="col-md-4">
                                <div class="form-check border rounded p-3">
                                    <input class="form-check-input" type="radio" name="strategy"
                                           id="stratPriceUp" value="PRICE_UP" checked>
                                    <label class="form-check-label" for="stratPriceUp">
                                        <strong><i class="bi bi-graph-up-arrow"></i> Price Up</strong>
                                        <br><small class="text-muted">Standard ascending bids</small>
                                    </label>
                                </div>
                            </div>
                            <div class="col-md-4">
                                <div class="form-check border rounded p-3">
                                    <input class="form-check-input" type="radio" name="strategy"
                                           id="stratLowStart" value="LOW_START_HIGH">
                                    <label class="form-check-label" for="stratLowStart">
                                        <strong><i class="bi bi-arrow-up-circle"></i> Low Start High</strong>
                                        <br><small class="text-muted">Start low, go high</small>
                                    </label>
                                </div>
                            </div>
                            <div class="col-md-4">
                                <div class="form-check border rounded p-3">
                                    <input class="form-check-input" type="radio" name="strategy"
                                           id="stratPublic" value="PUBLIC_BIDDING">
                                    <label class="form-check-label" for="stratPublic">
                                        <strong><i class="bi bi-people"></i> Public Bidding</strong>
                                        <br><small class="text-muted">Open public auction</small>
                                    </label>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div class="d-flex gap-2">
                        <button type="submit" class="btn btn-primary btn-lg">
                            <i class="bi bi-hammer"></i> Create Auction
                        </button>
                        <a href="${pageContext.request.contextPath}/product/list" class="btn btn-outline-secondary btn-lg">
                            Cancel
                        </a>
                    </div>
                </form>
            </div>
        </div>
    </div>
</div>

<jsp:include page="/common/footer.jsp"/>
