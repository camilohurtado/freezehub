package com.freezhub.demo;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a demo request (FZ-083).
 *
 * <p><strong>Stored before anything is sent, deliberately.</strong> The row is the lead;
 * the Slack message is a convenience on top of it. If the notification never gets through,
 * an operator can still find the request — which is what makes it acceptable to give up
 * retrying eventually, and what keeps a Slack outage from costing a customer.
 */
@Service
public class DemoRequestService {

    private static final Logger log = LoggerFactory.getLogger(DemoRequestService.class);

    private final DemoRequestRepository requests;

    public DemoRequestService(DemoRequestRepository requests) {
        this.requests = requests;
    }

    @Transactional
    public DemoRequest record(String name, String email, String company, String teamSize,
                              String message, String source, Instant now) {
        DemoRequest saved = requests.save(
                new DemoRequest(name, email, company, teamSize, message, source, now));

        // The id and nothing else. Name, email and employer belong to someone who is not
        // a customer, and a log line is the easiest place for that to leak.
        log.info("Demo request {} recorded", saved.getId());
        return saved;
    }
}
