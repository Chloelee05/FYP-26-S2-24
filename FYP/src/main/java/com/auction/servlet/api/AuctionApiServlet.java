package com.auction.servlet.api;

import com.auction.dao.AuctionTagsDAO;
import com.auction.dao.AutoBidDAO;
import com.auction.dao.BidDAO;
import com.auction.dao.BrowseHistoryDAO;
import com.auction.dao.OrderDAO;
import com.auction.dao.QuestionDAO;
import com.auction.model.AuctionDetail;
import com.auction.model.AuctionBidHistoryEntry;
import com.auction.model.AuctionQuestion;
import com.auction.model.AuctionType;
import com.auction.util.DutchClock;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GET  /api/auction/{id}            — public auction detail
 * GET  /api/auction/{id}/bids       — paginated bid history
 * GET  /api/auction/{id}/questions  — question list
 * POST /api/auction/upload-image    — upload a single listing image (SELLER auth required)
 *
 * <p>The read side of the auction detail page, which guests can reach, so it is mapped outside
 * AuthFilter. Authentication is still read where it exists, because what a viewer is allowed to
 * see depends on who they are: the seller sees their cost price and the standing sealed bid,
 * a bidder sees their own auto-bid ceiling and their own sealed amount, and everybody else
 * sees neither.</p>
 *
 * <p>This is one of the read paths where the BLIND confidentiality rule is applied, both in the
 * detail body and in the bid history. Collaborators: {@link BidDAO} for prices and bids,
 * {@link QuestionDAO}, {@link AuctionTagsDAO}, {@link AutoBidDAO}, {@link OrderDAO}, and
 * {@link BrowseHistoryDAO}, whose view log feeds the recommendation pipeline.</p>
 */
@WebServlet("/api/auction/*")
public class AuctionApiServlet extends ApiBase {

    private static final Set<String> ALLOWED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("image/jpeg", "image/png", "image/webp")));
    private static final String UPLOAD_SUBDIR = "auction";
    private static final String UPLOAD_DIR = UploadedFileServlet.BASE_DIR + File.separator + UPLOAD_SUBDIR;

    private final BidDAO            bidDAO            = new BidDAO();
    private final QuestionDAO       questionDAO       = new QuestionDAO();
    private final AuctionTagsDAO    tagsDAO           = new AuctionTagsDAO();
    private final BrowseHistoryDAO  browseHistoryDAO  = new BrowseHistoryDAO();
    private final AutoBidDAO        autoBidDAO        = new AutoBidDAO();
    private final OrderDAO          orderDAO          = new OrderDAO();

    /**
     * Routes the GET paths. The path is {@code /{id}} for the detail body, {@code /{id}/bids}
     * for bid history, {@code /{id}/questions} for the Q and A list, or the literal
     * {@code /tags}. No authentication is required for any of them.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo(); // e.g. /42  or  /42/bids
        if (pathInfo == null || pathInfo.equals("/")) {
            badRequest(resp, "Auction ID is required.");
            return;
        }

        String[] parts = pathInfo.split("/");
        // parts[0] = "" (leading slash), parts[1] = id or keyword, parts[2] = sub-resource (optional)
        if (parts.length < 2 || parts[1].isBlank()) {
            badRequest(resp, "Auction ID is required.");
            return;
        }

        // GET /api/auction/tags — return all available tags
        if ("tags".equals(parts[1])) {
            try { ok(resp, tagsDAO.getAllTags()); }
            catch (Exception e) { serverError(resp, "Could not load tags."); }
            return;
        }

        long auctionId;
        try {
            auctionId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            badRequest(resp, "Invalid auction ID.");
            return;
        }

        String sub = (parts.length >= 3) ? parts[2] : "";

        if ("bids".equals(sub)) {
            handleBidHistory(req, resp, auctionId);
        } else if ("questions".equals(sub)) {
            handleQuestions(resp, auctionId);
        } else {
            handleDetail(req, resp, auctionId);
        }
    }

    /**
     * Builds the auction detail body for GET /api/auction/{id}. What goes in depends on the
     * auction type and on who is looking, so the shared fields are assembled first and the
     * price-bearing fields are added per type below. 404 if the id does not exist.
     */
    private void handleDetail(HttpServletRequest req, HttpServletResponse resp, long auctionId) throws IOException {
        // Lazy close. Nothing runs a scheduler over expired auctions, so an auction that has
        // passed its end time is finalised on the first page view after expiry. That is what
        // makes the "open" flag and the revealed sealed price below correct.
        com.auction.util.AuctionFinalizer.finalizeIfExpiredAndNotify(auctionId);
        AuctionDetail detail = bidDAO.findByIdForDisplay(auctionId);
        if (detail == null) {
            error(resp, 404, "Auction not found.");
            return;
        }

        List<Map<String, Object>> tags = new ArrayList<>();
        try {
            for (Map.Entry<Long, String> e : tagsDAO.getTagsForAuction(auctionId)) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id",   e.getKey());
                t.put("name", e.getValue());
                tags.add(t);
            }
        } catch (Exception ignored) { }
        // Tags are decoration on this page, so a failure to load them must not 500 the listing.

        AuctionType type;
        try { type = AuctionType.getAuctionType(detail.getAuctionTypeId()); }
        catch (IllegalArgumentException e) { type = AuctionType.PRICE_UP; }

        boolean open = detail.isOpen();

        // Resolved before the type switch because a blind auction reveals its
        // standing bid to the seller who listed it, and to nobody else.
        Integer viewerId = sessionUserId(req);
        boolean isOwner = viewerId != null && viewerId == detail.getSellerId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id",             detail.getAuctionId());
        body.put("title",          detail.getTitle());
        body.put("description",    detail.getDescription());
        body.put("category",       detail.getCategory());
        body.put("condition",      detail.getCondition());
        // A buyer bidding on a service is not buying a shipped object. The page says so, and
        // it can only say so if the kind travels with the rest of the public detail.
        body.put("listingKind",    detail.getListingKind());
        body.put("quantity",       detail.getQuantity());
        body.put("startingPrice",  detail.getStartingPrice());
        body.put("reservePrice",   detail.getMaxPrice());
        body.put("buyItNowPrice",  detail.getBuyItNowPrice());
        body.put("endTime",        detail.getEndDate() != null ? detail.getEndDate().toString() : null);
        body.put("startTime",      detail.getDateCreated() != null ? detail.getDateCreated().toString() : null);
        body.put("sellerId",       detail.getSellerId());
        body.put("seller",         detail.getSellerUsername());
        body.put("images",         detail.getImageUrls());
        body.put("open",           open);
        body.put("tags",           tags);
        body.put("auctionType",    type.getId());
        body.put("auctionTypeName", auctionTypeName(type));

        switch (type) {
            case DUTCH_AUCTION:
                body.put("dutchFloorPrice", detail.getDutchFloorPrice());
                if (open) {
                    BigDecimal clock = DutchClock.currentPrice(
                            detail.getStartingPrice(), detail.getDutchFloorPrice(),
                            detail.getDateCreated(), detail.getEndDate(), Instant.now());
                    body.put("currentBid", clock);
                    body.put("numBids", 0);
                } else {
                    body.put("currentBid", detail.getCurrentBid());
                    body.put("numBids", detail.getBidCount());
                }
                break;
            case BLIND:
                // Sealed while open: hide the amount from buyers, expose only the
                // sealed-bid count. The seller sees the standing bid regardless —
                // "Declare Winner Early" sells at that price, so withholding it
                // would mean asking them to accept an amount they cannot see.
                body.put("currentBid", (open && !isOwner) ? null : detail.getCurrentBid());
                body.put("numBids", detail.getBidCount());
                body.put("sealed", open && !isOwner);
                break;
            case PRICE_UP:
            default:
                body.put("currentBid", detail.getCurrentBid());
                body.put("numBids", detail.getBidCount());
                break;
        }

        // Seller-private cost price: visible only to the auction owner.
        body.put("isOwner", isOwner);
        if (isOwner) {
            body.put("costPrice", detail.getCostPrice());
            // Declaring a winner is one-shot. Tell the owner's page whether it has
            // already happened so it can retire the button instead of offering one
            // whose only possible answer is "already finalised".
            if (!open) {
                try { body.put("orderCreated", orderDAO.existsForAuction(auctionId)); }
                catch (Exception ignored) { }
            }
        }

        // Buyer-private: inject myAutoBid if the viewer is a BUYER with an active auto-bid.
        // Exposed only to the bidder themselves — never to the seller or anonymous users.
        // Blind auctions are excluded: proxy bidding never runs on one, so echoing a row
        // stored before AutoBidApiServlet started refusing them would put an "Auto-Bid
        // Active" panel on a page where nothing is bidding on the buyer's behalf.
        if (viewerId != null && !isOwner && type != AuctionType.BLIND) {
            try {
                AutoBidDAO.AutoBidRow myRow = autoBidDAO.getAutoBidForUser(auctionId, viewerId);
                if (myRow != null) {
                    Map<String, Object> myAutoBid = new LinkedHashMap<>();
                    myAutoBid.put("enabled",      true);
                    myAutoBid.put("maxAmount",    myRow.getMaxAmount());
                    myAutoBid.put("bidIncrement", myRow.getIncrement());
                    body.put("myAutoBid", myAutoBid);
                }
            } catch (Exception ignored) { }
        }

        // Blind auctions: tell the current buyer whether they already submitted a
        // sealed bid so the UI can show a "submitted" state instead of the form.
        if (type == AuctionType.BLIND && open && viewerId != null && !isOwner) {
            try {
                BigDecimal mine = bidDAO.getUserBidAmount(auctionId, viewerId);
                body.put("mySealedBid", mine != null);
                if (mine != null) body.put("mySealedBidAmount", mine);
            } catch (Exception ignored) { }
        }

        // Log the view for the recommender. Only signed-in non-owners count: a guest has no
        // profile to attach it to, and a seller checking their own listing is not a taste signal.
        // Wrapped because a recommendation-side failure must never break the listing page.
        if (viewerId != null && !isOwner) {
            try {
                browseHistoryDAO.recordView(viewerId, auctionId);
            } catch (Exception ignored) { }
        }

        ok(resp, body);
    }

    /** Display label for the auction type, so the SPA does not have to map the numeric id itself. */
    private String auctionTypeName(AuctionType type) {
        switch (type) {
            case DUTCH_AUCTION: return "Dutch (Descending)";
            case BLIND:         return "Blind (Sealed Bid)";
            case PRICE_UP:
            default:            return "Standard (Ascending)";
        }
    }

    /**
     * GET /api/auction/{id}/bids. Optional {@code page} and {@code size} (default 10, capped
     * at 50). Returns the page of bids with {@code total}, {@code page} and {@code totalPages}.
     * While a sealed auction is open it returns an empty list with {@code sealed: true} and
     * only the count, which is the confidentiality guard on this read path.
     */
    private void handleBidHistory(HttpServletRequest req, HttpServletResponse resp, long auctionId)
            throws IOException {
        int page = parseInt(param(req, "page"), 1);
        int size = Math.min(parseInt(param(req, "size"), 10), 50);

        // Blind auctions hide all bids until the auction closes.
        AuctionDetail detail = bidDAO.findByIdForDisplay(auctionId);
        boolean blindStillOpen = detail != null
                && detail.getAuctionTypeId() == AuctionType.BLIND.getId()
                && detail.isOpen();
        if (blindStillOpen) {
            Map<String, Object> sealed = new LinkedHashMap<>();
            sealed.put("bids", Collections.emptyList());
            sealed.put("total", detail.getBidCount());
            sealed.put("page", 1);
            sealed.put("totalPages", 0);
            sealed.put("sealed", true);
            ok(resp, sealed);
            return;
        }

        // Pass viewer ID so the DAO can mark the viewer's own bids as isSelf=true.
        // 0 stands for a guest, and matches no real user id, so nothing gets flagged as theirs.
        Integer viewerId = sessionUserId(req);
        int viewerIdInt = viewerId != null ? viewerId : 0;
        List<AuctionBidHistoryEntry> bids  = bidDAO.getBidHistory(auctionId, page, size, viewerIdInt);
        int total = bidDAO.countBidHistory(auctionId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bids",       bids);
        body.put("total",      total);
        body.put("page",       page);
        body.put("totalPages", (int) Math.ceil((double) total / size));
        ok(resp, body);
    }

    /**
     * GET /api/auction/{id}/questions. Public buyer questions and the seller's answers for this
     * listing. Posting a question goes through {@code QuestionApiServlet} instead.
     */
    private void handleQuestions(HttpServletResponse resp, long auctionId) throws IOException {
        List<AuctionQuestion> questions = questionDAO.listByAuction(auctionId);
        ok(resp, questions);
    }

    /** The only POST here is the listing image upload; every other path gives 404. */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if ("/upload-image".equals(pathInfo)) {
            handleUploadImage(req, resp);
        } else {
            error(resp, 404, "Not found.");
        }
    }

    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024L; // 5 MB

    /**
     * POST /api/auction/upload-image. The request body is the raw image bytes and the content
     * type names the format. Requires a seller-capable session. Returns
     * {@code {"imageUrl": "/uploads/auction/..."}}, which the sell form then submits with the
     * rest of the listing.
     */
    private void handleUploadImage(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireSeller(req, resp)) return;

        // Allow-list of image types. Refusing anything else keeps a seller from parking an HTML
        // or script file under /uploads, which is served back to browsers by UploadedFileServlet.
        String contentType = req.getContentType();
        if (contentType == null) contentType = "";
        String mime = contentType.split(";")[0].trim().toLowerCase();
        if (!ALLOWED_TYPES.contains(mime)) {
            badRequest(resp, "Only JPEG, PNG, and WebP images are allowed.");
            return;
        }

        long len = req.getContentLengthLong();
        if (len > MAX_UPLOAD_BYTES) {
            badRequest(resp, "File too large (max 5 MB).");
            return;
        }

        // Name the file from a UUID and derive the extension from the vetted mime type. The
        // client's own filename is never used, so it cannot control the path or the extension.
        String ext = mime.contains("png") ? ".png" : mime.contains("webp") ? ".webp" : ".jpg";
        String filename = UUID.randomUUID() + ext;

        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) uploadDir.mkdirs();

        try (InputStream in = req.getInputStream()) {
            Files.copy(in, Paths.get(uploadDir.getAbsolutePath(), filename),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            serverError(resp, "Failed to save uploaded file: " + e.getMessage());
            return;
        }

        ok(resp, Collections.singletonMap("imageUrl", "/uploads/" + UPLOAD_SUBDIR + "/" + filename));
    }

    /** Parses a paging parameter, forced to at least 1 so a bad value cannot produce a negative offset. */
    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }
}
