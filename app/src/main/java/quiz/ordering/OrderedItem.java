package quiz.ordering;

import objectview.Viewable;

record OrderedItem(Viewable viewable, OrderValue value) {
    String id() {
        return viewable.getIdentifier();
    }
}
