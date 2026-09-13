package backend.storyweave;

public final class AllTests {
    private AllTests() {
    }

    public static void main(String[] arguments) throws Exception {
        JsonTest.run();
        GameEngineTest.run();
        ServerLoggerTest.run();
        RemoteStoryServiceTest.run();
        GameServerTest.run();
        System.out.println("All Storyweave tests passed.");
    }
}