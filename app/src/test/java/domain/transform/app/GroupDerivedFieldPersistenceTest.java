package domain.transform.app;

import objectview.ViewableAdapter;
import objectview.group.DefaultViewableGroup;
import objectview.viewconfig.DomainGroupRoot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Unanimous group fields remain projections of current members, never stale snapshot data. */
class GroupDerivedFieldPersistenceTest {

    @Test void aFieldDerivedFromMembersIsNotStoredOnTheGroup() {
        Person odin = new Person("Q43610", "Odin");
        Animal hugin = new Animal("Q59457190", "Hugin", odin);
        Animal munin = new Animal("Q59457345", "Munin", odin);
        AnimalGroup ravens = new AnimalGroup("Hugin and Munin");
        ravens.addMember(hugin, false);
        ravens.addMember(munin, false);

        ViewableToWdo.ConvertedDomain converted = ViewableToWdo.convertDomain(
                List.of(hugin, munin),
                List.of(new DomainGroupRoot("Animal", ravens)), null);

        assertFalse(converted.groupRoots().getFirst().dynamicFields().containsKey("owner"));
    }

    private static final class Person extends ViewableAdapter {
        private final String id;
        private final String label;
        private Person(String id, String label) {
            this.id = id;
            this.label = label;
        }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return label; }
        @Override public String identityTypeName() { return "Person"; }
    }

    private static final class Animal extends ViewableAdapter {
        private final String id;
        private final String label;
        private final Person owner;
        private Animal(String id, String label, Person owner) {
            this.id = id;
            this.label = label;
            this.owner = owner;
        }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return label; }
        @Override public String identityTypeName() { return "Animal"; }
    }

    private static final class AnimalGroup
            extends DefaultViewableGroup<Animal, AnimalGroup> {
        private AnimalGroup(String name) { super(name); }
        @Override protected AnimalGroup newChild(String name) {
            return new AnimalGroup(name);
        }
    }
}
