package nobel;

import java.util.List;

public interface MotivationTopicExtractor {
    List<String> extractTopics(String motivation) throws Exception;
}