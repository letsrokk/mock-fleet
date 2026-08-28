package com.github.letsrokk.mcp;

/** Marker types used to select stable MCP output schemas. */
public final class OutputSchemas {

    private OutputSchemas() {
    }

    public static final class ListMocks {}
    public static final class ListMockConfigs {}
    public static final class GetMockConfig {}
    public static final class ListOptionDefinitions {}
    public static final class UpdateMockConfig {}
    public static final class DeleteMockConfig {}
    public static final class StartMock {}
    public static final class StopMock {}
    public static final class StubPage {}
    public static final class Stub {}
    public static final class DeleteStub {}
    public static final class SendRequest {}
    public static final class RequestPage {}
    public static final class CountRequests {}
    public static final class NearMisses {}
    public static final class Reset {}
    public static final class RecordingStatus {}
    public static final class RecordingCandidates {}
    public static final class BodyFilePage {}
    public static final class GetBodyFile {}
    public static final class PutBodyFile {}
    public static final class DeleteBodyFile {}
    public static final class ScenarioPage {}
}
