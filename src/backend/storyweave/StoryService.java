package backend.storyweave;

import java.util.List;

public interface StoryService {
    String createStory(String theme, String storyContext, int version, int playerCount) throws Exception;

    int scoreSimilarity(String sharedStory, String referenceStory) throws Exception;

    int scoreDeduction(String sharedStory, List<GameEngine.Entry> entries) throws Exception;
}