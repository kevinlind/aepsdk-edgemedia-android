/*
  Copyright 2023 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.media.internal;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.adobe.marketing.mobile.Event;
import com.adobe.marketing.mobile.EventSource;
import com.adobe.marketing.mobile.EventType;
import com.adobe.marketing.mobile.ExtensionApi;
import com.adobe.marketing.mobile.ExtensionEventListener;
import com.adobe.marketing.mobile.Media;
import com.adobe.marketing.mobile.MediaConstants;
import com.adobe.marketing.mobile.MobileCore;
import com.adobe.marketing.mobile.SharedStateResult;
import com.adobe.marketing.mobile.SharedStateStatus;
import com.adobe.marketing.mobile.util.DataReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class MediaExtensionTests {

    MediaExtension mediaExtension;
    ExtensionApi mockExtensionAPI;
    MediaOfflineService mockOfflineService;
    MediaRealTimeService mockRealTimeService;
    MediaState mockMediaState;
    private MediaEventProcessor mockMediaEventProcessor;

    Map<String, ExtensionEventListener> eventListerMap;

    ExtensionEventListener getListener(String type, String source) {
        return eventListerMap.get(type + source);
    }

    Event getSharedStateEvent(String owner) {
        Map<String, Object> data = new HashMap<>();
        data.put(MediaTestConstants.STATE_OWNER, owner);
        return new Event.Builder("Shared state", EventType.HUB, EventSource.SHARED_STATE)
                .setEventData(data)
                .build();
    }

    public MediaExtensionTests() {
        mockExtensionAPI = mock(ExtensionApi.class);
        mediaExtension = new MediaExtension(mockExtensionAPI);

        mockOfflineService = mock(MediaOfflineService.class);
        mockRealTimeService = mock(MediaRealTimeService.class);
        mockMediaState = mock(MediaState.class);
        mockMediaEventProcessor = mock(MediaEventProcessor.class);

        eventListerMap = new HashMap<>();
        Mockito.doAnswer(
                        new Answer<Void>() {
                            @Override
                            public Void answer(final InvocationOnMock invocation) {
                                final Object[] args = invocation.getArguments();
                                String type = (String) args[0];
                                String source = (String) args[1];
                                ExtensionEventListener listener = (ExtensionEventListener) args[2];
                                eventListerMap.put(type + source, listener);
                                return null;
                            }
                        })
                .when(mockExtensionAPI)
                .registerEventListener(anyString(), anyString(), any(ExtensionEventListener.class));

        mediaExtension.onRegistered();

        mediaExtension.mediaRealTimeService = mockRealTimeService;
        mediaExtension.mediaOfflineService = mockOfflineService;
        mediaExtension.mediaState = mockMediaState;
        mediaExtension.mediaEventProcessor = mockMediaEventProcessor;
    }

    @Test
    public void testRealTimeTrackerCreation() {
        try (MockedStatic<MobileCore> mobileCoreMockedStatic =
                Mockito.mockStatic(MobileCore.class)) {
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            mobileCoreMockedStatic
                    .when(() -> MobileCore.dispatchEvent(eventCaptor.capture()))
                    .thenAnswer(Answers.RETURNS_DEFAULTS);

            Media.createTracker();
            Event event = eventCaptor.getValue();

            // Create tracker event
            ExtensionEventListener trackerListener =
                    getListener(
                            EventType.MEDIA, MediaTestConstants.Media.EVENT_SOURCE_TRACKER_REQUEST);
            trackerListener.hear(event);

            String trackerId =
                    DataReader.optString(
                            event.getEventData(), MediaTestConstants.EventDataKeys.Tracker.ID, "");
            MediaCollectionTracker tracker =
                    (MediaCollectionTracker) mediaExtension.trackers.get(trackerId);
            assertNotNull(tracker);
            assertEquals(mockRealTimeService, tracker.getHitProcessor());
        }
    }

    @Test
    public void testOfflineTrackerCreation() {
        try (MockedStatic<MobileCore> mobileCoreMockedStatic =
                Mockito.mockStatic(MobileCore.class)) {
            ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
            mobileCoreMockedStatic
                    .when(() -> MobileCore.dispatchEvent(eventCaptor.capture()))
                    .thenAnswer(Answers.RETURNS_DEFAULTS);

            Map<String, Object> config = new HashMap<>();
            config.put(MediaConstants.Config.DOWNLOADED_CONTENT, true);

            Media.createTracker(config);
            Event event = eventCaptor.getValue();

            // Create tracker event
            ExtensionEventListener trackerListener =
                    getListener(
                            EventType.MEDIA, MediaTestConstants.Media.EVENT_SOURCE_TRACKER_REQUEST);
            trackerListener.hear(event);

            String trackerId =
                    DataReader.optString(
                            event.getEventData(), MediaTestConstants.EventDataKeys.Tracker.ID, "");
            MediaCollectionTracker tracker =
                    (MediaCollectionTracker) mediaExtension.trackers.get(trackerId);
            assertNotNull(tracker);
            assertEquals(mockOfflineService, tracker.getHitProcessor());
        }
    }

    @Test
    public void testMediaTrackWithTrackerPresent() {
        MediaTrackerInterface tracker = mock(MediaTrackerInterface.class);
        mediaExtension.trackers.put("key", tracker);

        Event event =
                new Event.Builder(
                                "",
                                EventType.MEDIA,
                                MediaTestConstants.Media.EVENT_SOURCE_TRACK_MEDIA)
                        .setEventData(
                                new HashMap<String, Object>() {
                                    {
                                        put(MediaTestConstants.EventDataKeys.Tracker.ID, "key");
                                    }
                                })
                        .build();

        ExtensionEventListener trackListener =
                getListener(EventType.MEDIA, MediaTestConstants.Media.EVENT_SOURCE_TRACK_MEDIA);
        trackListener.hear(event);

        verify(tracker, times(1)).track(event);
    }

    @Test
    public void testMediaTrackWithTrackerAbsent() {
        Event event =
                new Event.Builder(
                                "",
                                EventType.MEDIA,
                                MediaTestConstants.Media.EVENT_SOURCE_TRACK_MEDIA)
                        .setEventData(
                                new HashMap<String, Object>() {
                                    {
                                        put(MediaTestConstants.EventDataKeys.Tracker.ID, "key");
                                    }
                                })
                        .build();

        ExtensionEventListener trackListener =
                getListener(EventType.MEDIA, MediaTestConstants.Media.EVENT_SOURCE_TRACK_MEDIA);
        try {
            trackListener.hear(event);
        } catch (Exception ex) {
            fail();
        }
    }

    @Test
    public void testRequestReset() {
        Event event =
                new Event.Builder("", EventType.GENERIC_IDENTITY, EventSource.REQUEST_RESET)
                        .build();

        ExtensionEventListener resetListener =
                getListener(EventType.GENERIC_IDENTITY, EventSource.REQUEST_RESET);
        resetListener.hear(event);

        verify(mockOfflineService, times(1)).reset();
        verify(mockRealTimeService, times(1)).reset();
    }

    @Test
    public void testValidSharedStateUpdates() {
        String[] validExtensions =
                new String[] {
                    MediaTestConstants.Configuration.SHARED_STATE_NAME,
                    MediaTestConstants.Analytics.SHARED_STATE_NAME,
                    MediaTestConstants.Assurance.SHARED_STATE_NAME,
                    MediaTestConstants.Identity.SHARED_STATE_NAME
                };

        for (String extension : validExtensions) {
            SharedStateResult res = new SharedStateResult(SharedStateStatus.SET, new HashMap<>());
            doReturn(res).when(mockExtensionAPI).getSharedState(any(), any(), anyBoolean(), any());

            Event event = getSharedStateEvent(extension);
            ExtensionEventListener sharedStateListener =
                    getListener(EventType.HUB, EventSource.SHARED_STATE);
            sharedStateListener.hear(event);

            verify(mockOfflineService, times(1)).notifyMobileStateChanges();
            verify(mockRealTimeService, times(1)).notifyMobileStateChanges();
            verify(mockMediaState, times(1))
                    .notifyMobileStateChanges(eq(extension), eq(res.getValue()));

            Mockito.reset(mockOfflineService);
            Mockito.reset(mockRealTimeService);
            Mockito.reset(mockMediaState);
        }
    }

    @Test
    public void testSharedStateUpdates_NotSet() {
        String configuration = MediaTestConstants.Configuration.SHARED_STATE_NAME;
        Event event = getSharedStateEvent(configuration);

        ExtensionEventListener sharedStateListener =
                getListener(EventType.HUB, EventSource.SHARED_STATE);

        SharedStateResult res = new SharedStateResult(SharedStateStatus.PENDING, new HashMap<>());
        doReturn(res).when(mockExtensionAPI).getSharedState(any(), any(), anyBoolean(), any());
        sharedStateListener.hear(event);

        res = new SharedStateResult(SharedStateStatus.NONE, null);
        doReturn(res).when(mockExtensionAPI).getSharedState(any(), any(), anyBoolean(), any());
        sharedStateListener.hear(event);

        verify(mockOfflineService, times(0)).notifyMobileStateChanges();
        verify(mockRealTimeService, times(0)).notifyMobileStateChanges();
        verify(mockMediaState, times(0)).notifyMobileStateChanges(eq(configuration), any());
    }

    @Test
    public void testSharedStateUpdates_OtherExtensions() {
        String audience = MediaTestConstants.Audience.SHARED_STATE_NAME;
        Event event = getSharedStateEvent(audience);

        ExtensionEventListener sharedStateListener =
                getListener(EventType.HUB, EventSource.SHARED_STATE);

        SharedStateResult res = new SharedStateResult(SharedStateStatus.SET, new HashMap<>());
        doReturn(res).when(mockExtensionAPI).getSharedState(any(), any(), anyBoolean(), any());
        sharedStateListener.hear(event);

        verify(mockOfflineService, times(0)).notifyMobileStateChanges();
        verify(mockRealTimeService, times(0)).notifyMobileStateChanges();
        verify(mockMediaState, times(0)).notifyMobileStateChanges(eq(audience), any());
    }

    @Test
    public void testHandleEdgeMediaSessionDetails_validRequestId_validSessionId_callsEventProcessor() {
        final String expectedRequestEventId = "event123";
        final String expectedBackendSessionId = "99cf4e3e7145d8e2b8f4f1e9e1a08cd52518a74091c0b0c611ca97b259e03a4d";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("payload", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("sessionId", expectedBackendSessionId);
            }});
        }});
        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(1)).notifyBackendSessionId(expectedRequestEventId, expectedBackendSessionId);
    }

    @Test
    public void testHandleEdgeMediaSessionDetails_validRequestId_noSessionId_callsEventProcessor() {
        final String expectedRequestEventId = "event123";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("payload", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("invalid", "no session id");
            }});
        }});
        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(1)).notifyBackendSessionId(expectedRequestEventId, null);
    }

    @Test
    public void testHandleEdgeMediaSessionDetails_validRequestId_emptySessionId_callsEventProcessor() {
        final String expectedRequestEventId = "event123";
        final String expectedBackendSessionId = "";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("payload", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("sessionId", expectedBackendSessionId);
            }});
        }});
        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(1)).notifyBackendSessionId(expectedRequestEventId, expectedBackendSessionId);
    }

    @Test
    public void testHandleEdgeMediaSessionDetails_validRequestId_noPayload_callsEventProcessor() {
        final String expectedRequestEventId = "event123";

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);

        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(1)).notifyBackendSessionId(expectedRequestEventId, null);
    }

    @Test
    public void testHandleEdgeMediaSessionDetails_noRequestId_doesNotCallEventProcessor() {
        final String expectedBackendSessionId = "99cf4e3e7145d8e2b8f4f1e9e1a08cd52518a74091c0b0c611ca97b259e03a4d";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("payload", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("sessionId", expectedBackendSessionId);
            }});
        }});
        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(0)).notifyBackendSessionId(any(), any());
    }

    @Test
    public void testHandleEdgeMediaSessionDetails_nullRequestId_doesNotCallEventProcessor() {
        final String expectedRequestEventId = null;
        final String expectedBackendSessionId = "99cf4e3e7145d8e2b8f4f1e9e1a08cd52518a74091c0b0c611ca97b259e03a4d";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("payload", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("sessionId", expectedBackendSessionId);
            }});
        }});
        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(0)).notifyBackendSessionId(any(), any());
    }

    @Test
    public void testHandleEdgeMediaSessionDetails_emptyRequestId_doesNotCallEventProcessor() {
        final String expectedRequestEventId = "";
        final String expectedBackendSessionId = "99cf4e3e7145d8e2b8f4f1e9e1a08cd52518a74091c0b0c611ca97b259e03a4d";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("payload", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("sessionId", expectedBackendSessionId);
            }});
        }});
        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_MEDIA_EDGE_SESSION);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(0)).notifyBackendSessionId(any(), any());
    }

    @Test
    public void testHandleEdgeErrorResponse_validRequestId_validEventData_callsEventProcessor() {
        final String expectedRequestEventId = "event123";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("errors", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("type", "https://ns.adobe.com/aep/errors/va-edge-0404-404");
                put("status", 404);
                put("title", "Not Found");
            }});
        }});

        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(1)).notifyErrorResponse(expectedRequestEventId, event.getEventData());
    }

    @Test
    public void testHandleEdgeErrorResponse_noRequestId_validEventData_doesNotCallEventProcessor() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("errors", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("type", "https://ns.adobe.com/aep/errors/va-edge-0404-404");
                put("status", 404);
                put("title", "Not Found");
            }});
        }});

        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(0)).notifyErrorResponse(any(), any());
    }

    @Test
    public void testHandleEdgeErrorResponse_nullRequestId_validEventData_doesNotCallEventProcessor() {
        final String expectedRequestEventId = null;
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("errors", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("type", "https://ns.adobe.com/aep/errors/va-edge-0404-404");
                put("status", 404);
                put("title", "Not Found");
            }});
        }});

        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(0)).notifyErrorResponse(any(), any());
    }

    @Test
    public void testHandleEdgeErrorResponse_emptyRequestId_validEventData_doesNotCallEventProcessor() {
        final String expectedRequestEventId = "";
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("requestEventId", expectedRequestEventId);
        eventData.put("errors", new ArrayList<Map<String, Object>>(){{
            add(new HashMap<String, Object>(){{
                put("type", "https://ns.adobe.com/aep/errors/va-edge-0404-404");
                put("status", 404);
                put("title", "Not Found");
            }});
        }});

        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE)
                .setEventData(eventData)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(0)).notifyErrorResponse(any(), any());
    }

    @Test
    public void testHandleEdgeErrorResponse_noEventData_doesNotCallEventProcessor() {
        Event event = new Event.Builder("Edge Media Session", EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE)
                .build();

        ExtensionEventListener listener = getListener(EventType.EDGE, MediaInternalConstants.Media.EVENT_SOURCE_EDGE_ERROR_RESOURCE);
        listener.hear(event);

        verify(mockMediaEventProcessor, times(0)).notifyErrorResponse(any(), any());
    }
}
