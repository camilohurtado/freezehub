package com.freezhub.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "organization")
public class Organization {

    /** 24 hours: a day's notice is how far ahead teams actually plan around a freeze. */
    public static final int DEFAULT_STARTING_SOON_LEAD_TIME_MINUTES = 1440;

    /** Matches the database CHECK, so the two cannot drift. */
    public static final int MIN_STARTING_SOON_LEAD_TIME_MINUTES = 1;
    public static final int MAX_STARTING_SOON_LEAD_TIME_MINUTES = 30 * 24 * 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /**
     * How far ahead of a freeze this organization wants warning, in minutes (FZ-047).
     *
     * <p>Per-organization because release rhythms differ: a weekly train wants more
     * notice than a shop deploying continuously. Defaulted rather than asked for at
     * sign-up, so nobody has to answer a question they have no opinion about yet.
     */
    @Column(name = "starting_soon_lead_time_minutes", nullable = false)
    private int startingSoonLeadTimeMinutes = DEFAULT_STARTING_SOON_LEAD_TIME_MINUTES;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    public Organization(String name) {
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getStartingSoonLeadTimeMinutes() {
        return startingSoonLeadTimeMinutes;
    }

    /**
     * Zero would make the warning fire as the freeze begins, which {@code ACTIVATED}
     * already covers; the upper bound is well past any window anyone plans a freeze over.
     * Enforced here as well as by the database so the rejection is a 400 rather than a 500.
     */
    public void setStartingSoonLeadTimeMinutes(int minutes) {
        if (minutes < MIN_STARTING_SOON_LEAD_TIME_MINUTES || minutes > MAX_STARTING_SOON_LEAD_TIME_MINUTES) {
            throw new IllegalArgumentException("Lead time must be between "
                    + MIN_STARTING_SOON_LEAD_TIME_MINUTES + " and "
                    + MAX_STARTING_SOON_LEAD_TIME_MINUTES + " minutes");
        }
        this.startingSoonLeadTimeMinutes = minutes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
