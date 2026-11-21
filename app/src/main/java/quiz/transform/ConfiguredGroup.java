package quiz.transform;

/**
 * A group that the model's group class configures. The model and the generated
 * instances define it, so it is rebuilt from them on every load. A working tree
 * holds it as it is, and Save never writes it. A saved copy would freeze its
 * membership and come back beside the rebuilt group.
 */
public interface ConfiguredGroup { }
