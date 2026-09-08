package com.freezhub.notification;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationRepository;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What was announced, and whether each channel accepted it (`FZ-115`).
 *
 * <p>The outbox stores one row per channel per event, which is right for sending and
 * wrong for reading: three rows that say the same thing happened, one of which quietly
 * says it did not arrive. This groups them back into the event they describe.
 */
@Service
public class NotificationHistoryService {

    private final NotificationRepository notifications;
    private final ChangeRestrictionRepository restrictions;
    private final IntegrationRepository integrations;

    public NotificationHistoryService(NotificationRepository notifications,
                                      ChangeRestrictionRepository restrictions,
                                      IntegrationRepository integrations) {
        this.notifications = notifications;
        this.restrictions = restrictions;
        this.integrations = integrations;
    }

    @Transactional(readOnly = true)
    public List<NotificationEventResponse> history(Long organizationId, int limit) {
        List<Notification> rows = notifications
                .findAllByOrganizationIdOrderByIdDesc(organizationId);
        if (rows.isEmpty()) {
            return List.of();
        }

        // Two lookups rather than one per row: a page of history touches a handful of
        // restrictions and channels, and asking per row is how a list view becomes slow
        // without anything looking wrong.
        Map<Long, String> restrictionNames = restrictions
                .findAllById(rows.stream().map(Notification::getRestrictionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ChangeRestriction::getId, ChangeRestriction::getName));
        Map<Long, Integration> channels = integrations
                .findAllById(rows.stream().map(Notification::getIntegrationId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Integration::getId, Function.identity()));

        // Insertion-ordered, so the newest event stays first — the rows arrive newest-first
        // and the first row of a group decides where the group sits.
        Map<String, List<Notification>> grouped = new LinkedHashMap<>();
        for (Notification row : rows) {
            grouped.computeIfAbsent(row.getRestrictionId() + ":" + row.getEvent(),
                    key -> new ArrayList<>()).add(row);
        }

        List<NotificationEventResponse> events = new ArrayList<>();
        for (var group : grouped.values()) {
            if (events.size() >= limit) {
                break;
            }
            Notification first = group.getFirst();
            events.add(new NotificationEventResponse(
                    first.getRestrictionId(),
                    // Total rather than optional, but not a feature: {@code notification}
                    // cascades on {@code restriction_id}, and cancelling is a status
                    // change rather than a delete (FZ-024), so a history row without its
                    // restriction cannot occur. The default is here so that a future
                    // schema change surfaces as odd text rather than as a null.
                    restrictionNames.getOrDefault(first.getRestrictionId(), "(removed restriction)"),
                    first.getEvent(),
                    group.stream().map(Notification::getCreatedAt).min(Comparator.naturalOrder())
                            .orElse(first.getCreatedAt()),
                    group.stream()
                            .map(row -> toDelivery(row, channels))
                            .toList()));
        }
        return events;
    }

    private NotificationEventResponse.Delivery toDelivery(Notification row,
                                                          Map<Long, Integration> channels) {
        Integration channel = channels.get(row.getIntegrationId());
        return new NotificationEventResponse.Delivery(
                row.getIntegrationId(),
                channel == null ? null : channel.getType(),
                row.getStatus(),
                row.getAttempts(),
                row.getLastError(),
                row.getSentAt());
    }
}
