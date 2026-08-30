# One presentation per semantic entry

An entity, property, category, document, or other semantic entry has one
canonical interactive presentation. Every workbench surface renders that
presentation unchanged.

The entry type owns the facts a reader recognizes: stable identifier, display
label, description or other distinguishing metadata, and its source link. A
host panel may choose card/table layout, single or multiple selection, search,
and context-specific actions. It must not reconstruct the entry as a private
string or invent a second row/card type.

Consequences:

- moving an entry through reusable selections does not discard presentation
  facts already known about it;
- source identifiers remain links wherever the entry appears;
- vocabularies, discovery results, pickers, and review panels use the same
  field names and ordering for the canonical facts they retain. A host must
  omit metadata it cannot carry durably rather than render a misleading empty
  placeholder;
- compact layouts hide canonical fields through view configuration; they do
  not replace the canonical presentation with ad-hoc text;
- logs and exports may use a text projection, but that projection is not an
  interactive UI representation.

When a new entry kind is introduced, define its canonical presentation first
and make every producer return it. Context supplies behavior; the semantic
value supplies appearance.
