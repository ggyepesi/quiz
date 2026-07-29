package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import objectview.Viewable;
import quiz.transform.DynamicViewable;
import objectview.field.FieldAccess;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Joiner equi-joins two classes on a key value, producing a new class whose
 * instances reference both sides (so nested paths expose both).
 */
class JoinerTest {

    @Test void joinsTwoClassesOnAValueKey() {
        DynamicViewable c1 = new DynamicViewable("C1", "Alice");
        c1.type("Customer");
        c1.put("customerKey", "C1");
        c1.put("cname", "Alice");

        DynamicViewable order = new DynamicViewable("O1", "Order 1");
        order.type("Order");
        order.put("customerId", "C1");
        order.put("total", 42);

        DynamicViewable orphan = new DynamicViewable("O2", "Order 2");
        orphan.type("Order");
        orphan.put("customerId", "C9");   // no matching customer

        DomainModel domain = new InMemory(List.of(order, orphan, c1));

        DerivedClass joined = Joiner.join(domain, "OrderCustomer",
                "Order", "customerId", "Customer", "customerKey");

        assertEquals(2, joined.instances().size());   // both orders (matched or not)

        DynamicViewable row = joined.instances().stream()
                .map(DynamicViewable.class::cast)
                .filter(o -> o.getIdentifier().startsWith("O1")).findFirst().orElseThrow();
        assertNotNull(row.get("order"));
        assertNotNull(row.get("customer"));
        // Nested paths expose BOTH sides.
        assertEquals(42, FieldAccess.getPath(row, "order.total"));
        assertEquals("Alice", FieldAccess.getPath(row, "customer.cname"));

        DynamicViewable orphanRow = joined.instances().stream()
                .map(DynamicViewable.class::cast)
                .filter(o -> o.getIdentifier().startsWith("O2")).findFirst().orElseThrow();
        assertNotNull(orphanRow.get("order"));
        assertNull(orphanRow.get("customer"));   // no match
    }

    private record InMemory(List<? extends Viewable> items) implements DomainModel {
        @Override public List<String> types() {
            return items.stream().map(Viewable::typeName).distinct().toList();
        }
        @Override public List<DomainField> fields(String type) { return List.of(); }
        @Override public Collection<? extends Viewable> instances() { return items; }
        @Override public Class<? extends Viewable> universe() { return Viewable.class; }
    }
}
