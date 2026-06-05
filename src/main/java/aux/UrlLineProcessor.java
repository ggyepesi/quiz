package aux;

import java.net.URL;

public interface UrlLineProcessor<T> {
      // Decides if the line read from a wiki url contains relevant information.
      // Saves the object of type T representing the information extracted from line if there is any.
      // Returns if the line is a redirect (sse CoatOfArmsProcessor) then return the redirect url otherwise return null.
      public URL processLine(String line) throws Exception;

      // Returns true if processing done, no more line should be passed to processLine.
      public boolean isDone();

      // Returns the object representing the information extracted from the lines via processLine.
      public T done() throws Exception;
}
