import com.auction.dao.OrderDAO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OrderDAO – shipping label helper")
class TestOrderDAOLabels {

    @Test
    @DisplayName("labelForShipping maps known statuses")
    void labels() {
        assertEquals("Seller preparing your order", OrderDAO.labelForShipping("PREPARING"));
        assertEquals("Package shipped", OrderDAO.labelForShipping("SHIPPED"));
        assertEquals("Out for delivery", OrderDAO.labelForShipping("IN_TRANSIT"));
        assertEquals("Delivered", OrderDAO.labelForShipping("DELIVERED"));
        assertEquals("Pending", OrderDAO.labelForShipping(null));
    }
}
