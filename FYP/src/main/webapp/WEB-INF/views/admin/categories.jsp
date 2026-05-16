<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Category Management — Admin</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/admin.css">
</head>
<body class="admin-body">
<div class="admin-layout">
    <%@ include file="/WEB-INF/includes/admin-sidebar.jspf" %>
    <main class="admin-main flex-grow-1 p-3 p-lg-4">
        <header class="d-flex align-items-center justify-content-between mb-4">
            <div>
                <h1 class="admin-page-title mb-1">Category Management</h1>
                <p class="admin-subtitle mb-0">Create, edit, and deactivate auction categories.</p>
            </div>
            <button class="btn btn-primary btn-sm" data-bs-toggle="modal" data-bs-target="#createModal">
                <i class="bi bi-plus-lg me-1"></i>New Category
            </button>
        </header>

        <c:if test="${not empty adminFlash}">
            <div class="alert alert-success py-2 small"><c:out value="${adminFlash}"/></div>
        </c:if>
        <c:if test="${not empty adminFlashError}">
            <div class="alert alert-danger py-2 small"><c:out value="${adminFlashError}"/></div>
        </c:if>

        <div class="admin-table-card">
            <div class="table-responsive">
                <table class="table table-hover align-middle mb-0">
                    <thead class="table-light">
                    <tr>
                        <th scope="col">Name</th>
                        <th scope="col" class="d-none d-md-table-cell">Slug</th>
                        <th scope="col" class="d-none d-lg-table-cell">Description</th>
                        <th scope="col" class="text-center">Order</th>
                        <th scope="col" class="text-center">Auctions</th>
                        <th scope="col">Status</th>
                        <th scope="col" class="text-end">Actions</th>
                    </tr>
                    </thead>
                    <tbody>
                    <c:forEach var="cat" items="${categories}">
                        <tr class="${cat.deleted ? 'table-secondary' : ''}">
                            <td class="fw-semibold"><c:out value="${cat.name}"/></td>
                            <td class="small text-muted d-none d-md-table-cell"><c:out value="${cat.slug}"/></td>
                            <td class="small d-none d-lg-table-cell">
                                <c:choose>
                                    <c:when test="${not empty cat.description}"><c:out value="${cat.description}"/></c:when>
                                    <c:otherwise><span class="text-muted">—</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-center">${cat.displayOrder}</td>
                            <td class="text-center">
                                <span class="badge ${cat.auctionCount > 0 ? 'text-bg-info' : 'text-bg-secondary'}">
                                    ${cat.auctionCount}
                                </span>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${cat.deleted}"><span class="badge text-bg-secondary">deactivated</span></c:when>
                                    <c:otherwise><span class="badge text-bg-success">active</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-end">
                                <div class="d-flex flex-wrap gap-1 justify-content-end">
                                    <c:if test="${!cat.deleted}">
                                        <%-- Edit button — passes data to modal via JS --%>
                                        <button type="button" class="btn btn-outline-secondary btn-sm"
                                                data-bs-toggle="modal" data-bs-target="#editModal"
                                                data-cat-id="${cat.id}"
                                                data-cat-name="<c:out value="${cat.name}"/>"
                                                data-cat-desc="<c:out value="${cat.description}"/>"
                                                data-cat-order="${cat.displayOrder}">
                                            Edit
                                        </button>
                                        <%-- Delete — restricted if auctions exist --%>
                                        <form class="d-inline" method="post"
                                              action="${pageContext.request.contextPath}/admin/categories">
                                            <input type="hidden" name="action" value="DELETE"/>
                                            <input type="hidden" name="categoryId" value="${cat.id}"/>
                                            <c:choose>
                                                <c:when test="${cat.auctionCount > 0}">
                                                    <button type="submit" class="btn btn-danger btn-sm" disabled
                                                            title="Category has ${cat.auctionCount} auction(s). Recategorize them first.">
                                                        Delete
                                                    </button>
                                                </c:when>
                                                <c:otherwise>
                                                    <button type="submit" class="btn btn-danger btn-sm"
                                                            data-confirm="Deactivate category &quot;<c:out value="${cat.name}"/>&quot;?">
                                                        Delete
                                                    </button>
                                                </c:otherwise>
                                            </c:choose>
                                        </form>
                                    </c:if>
                                    <c:if test="${cat.deleted}">
                                        <form class="d-inline" method="post"
                                              action="${pageContext.request.contextPath}/admin/categories">
                                            <input type="hidden" name="action" value="RESTORE"/>
                                            <input type="hidden" name="categoryId" value="${cat.id}"/>
                                            <button type="submit" class="btn btn-success btn-sm"
                                                    data-confirm="Restore category &quot;<c:out value="${cat.name}"/>&quot;?">
                                                Restore
                                            </button>
                                        </form>
                                    </c:if>
                                </div>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty categories}">
                        <tr><td colspan="7" class="text-center text-muted py-4">No categories yet.</td></tr>
                    </c:if>
                    </tbody>
                </table>
            </div>
        </div>
    </main>
</div>

<%-- ===== Create Modal ===== --%>
<div class="modal fade" id="createModal" tabindex="-1" aria-labelledby="createModalLabel" aria-hidden="true">
    <div class="modal-dialog">
        <form method="post" action="${pageContext.request.contextPath}/admin/categories">
            <input type="hidden" name="action" value="CREATE"/>
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title" id="createModalLabel">New Category</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                </div>
                <div class="modal-body">
                    <div class="mb-3">
                        <label for="createName" class="form-label">Name <span class="text-danger">*</span></label>
                        <input type="text" class="form-control" id="createName" name="name"
                               maxlength="100" required placeholder="e.g. Electronics">
                    </div>
                    <div class="mb-3">
                        <label for="createDesc" class="form-label">Description <span class="text-muted small">(optional)</span></label>
                        <textarea class="form-control" id="createDesc" name="description"
                                  rows="2" maxlength="500" placeholder="Short description…"></textarea>
                    </div>
                    <div class="mb-3">
                        <label for="createOrder" class="form-label">Display Order</label>
                        <input type="number" class="form-control" id="createOrder" name="displayOrder"
                               value="0" min="0" max="9999">
                        <div class="form-text">Lower numbers appear first in listings.</div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button type="submit" class="btn btn-primary">Create Category</button>
                </div>
            </div>
        </form>
    </div>
</div>

<%-- ===== Edit Modal ===== --%>
<div class="modal fade" id="editModal" tabindex="-1" aria-labelledby="editModalLabel" aria-hidden="true">
    <div class="modal-dialog">
        <form method="post" action="${pageContext.request.contextPath}/admin/categories">
            <input type="hidden" name="action" value="EDIT"/>
            <input type="hidden" name="categoryId" id="editCategoryId"/>
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title" id="editModalLabel">Edit Category</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                </div>
                <div class="modal-body">
                    <div class="mb-3">
                        <label for="editName" class="form-label">Name <span class="text-danger">*</span></label>
                        <input type="text" class="form-control" id="editName" name="name"
                               maxlength="100" required>
                    </div>
                    <div class="mb-3">
                        <label for="editDesc" class="form-label">Description <span class="text-muted small">(optional)</span></label>
                        <textarea class="form-control" id="editDesc" name="description"
                                  rows="2" maxlength="500"></textarea>
                    </div>
                    <div class="mb-3">
                        <label for="editOrder" class="form-label">Display Order</label>
                        <input type="number" class="form-control" id="editOrder" name="displayOrder"
                               min="0" max="9999">
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
                    <button type="submit" class="btn btn-primary">Save Changes</button>
                </div>
            </div>
        </form>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/admin-panel.js"></script>
<script>
    // Populate edit modal from data attributes on the Edit button
    document.getElementById('editModal').addEventListener('show.bs.modal', function (event) {
        var btn = event.relatedTarget;
        document.getElementById('editCategoryId').value = btn.dataset.catId;
        document.getElementById('editName').value = btn.dataset.catName;
        document.getElementById('editDesc').value = btn.dataset.catDesc || '';
        document.getElementById('editOrder').value = btn.dataset.catOrder || 0;
    });
</script>
</body>
</html>
