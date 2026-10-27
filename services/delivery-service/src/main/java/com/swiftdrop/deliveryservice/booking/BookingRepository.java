package com.swiftdrop.deliveryservice.booking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative persistence adapter for atomic parcel booking transactions.
 * Aligned strictly with canonical Delivery schema (parcels, status_history, outbox_events, idempotency_records).
 * Implements advisory locking, de-duplication replay, and single-consumption quote enforcement.
 */
@Repository
public class BookingRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public BookingRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Replay(String digest, ParcelBooking response, String storedResultJson) {
        public Replay(String digest, ParcelBooking response) {
            this(digest, response, null);
        }
    }

    public void lock(String principalOrTenant, UUID key) {
        // Advisory xact lock guarantees deterministic serialization of concurrent identical requests
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, principalOrTenant + ":" + key);
    }

    public Optional<Replay> replay(String subject, UUID key) {
        return jdbc.query("SELECT request_digest, response_json::text FROM idempotency_records WHERE actor_sub=? AND idempotency_key=?",
                (rs, n) -> new Replay(rs.getString(1), json.readValue(rs.getString(2), ParcelBooking.class)), subject, key).stream().findFirst();
    }

    public Optional<Replay> replayMerchant(UUID merchantId, UUID key) {
        return jdbc.query("""
                SELECT request_digest, stored_result::text
                FROM idempotency_records
                WHERE principal_or_tenant = ? AND idempotency_key = ?
                """,
                (rs, n) -> new Replay(rs.getString(1), null, rs.getString(2)),
                merchantId.toString(), key.toString()
        ).stream().findFirst();
    }

    public boolean quoteUsed(UUID quoteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM parcels WHERE quote_id = ?)",
                Boolean.class,
                quoteId
        ));
    }

    public boolean insert(ParcelBooking p, BookParcelRequest r, String snapshot) {
        return jdbc.update("""
                INSERT INTO parcels (id,tracking_number,created_by_sub,quote_id,current_status,version,
                    pickup_address,dropoff_address,recipient_name,recipient_phone,delivery_notes,
                    amount_minor_units,currency,rate_version,quote_snapshot,created_at)
                VALUES (?,?,?,?, 'CREATED',1,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING
                """, p.parcelId(), p.trackingNumber(), p.createdBySub(), r.quoteId(), r.pickupAddress(), r.dropoffAddress(),
                r.recipientName(), r.recipientPhone(), r.deliveryNotes(), p.amountMinorUnits(), p.currency(), p.rateVersion(), snapshot,
                java.sql.Timestamp.from(p.createdAt())) == 1;
    }

    public boolean insertMerchantParcel(UUID parcelId,
                                        String trackingNumber,
                                        String senderSub,
                                        UUID merchantId,
                                        UUID quoteId,
                                        String rateVersion,
                                        long amountMinorUnits,
                                        String currency,
                                        String merchantReference,
                                        String pickupAddress,
                                        String dropoffAddress,
                                        String recipientName,
                                        String recipientPhone,
                                        String deliveryNotes,
                                        String snapshot,
                                        Instant createdAt) {
        Timestamp ts = Timestamp.from(createdAt);
        return jdbc.update("""
                INSERT INTO parcels (
                    parcel_id, tracking_number, sender_sub, merchant_id, quote_id,
                    rate_version, amount_minor_units, currency, status, version,
                    merchant_reference, pickup_address, dropoff_address, recipient_name,
                    recipient_phone, delivery_notes, quote_snapshot, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, 'CREATED', 1,
                    ?, ?, ?, ?,
                    ?, ?, ?::jsonb, ?, ?
                ) ON CONFLICT DO NOTHING
                """,
                parcelId, trackingNumber, senderSub, merchantId, quoteId,
                rateVersion, amountMinorUnits, currency,
                merchantReference, pickupAddress, dropoffAddress, recipientName,
                recipientPhone, deliveryNotes, snapshot, ts, ts
        ) == 1;
    }

    public void finish(ParcelBooking p, UUID key, String digest, UUID eventId, String event) {
        jdbc.update("INSERT INTO status_history (id,parcel_id,state_version,old_status,new_status,occurred_at) VALUES (?,?,1,NULL,'CREATED',?)",
                UUID.randomUUID(), p.parcelId(), java.sql.Timestamp.from(p.createdAt()));
        jdbc.update("INSERT INTO outbox_events (event_id,parcel_id,aggregate_version,event_type,payload,created_at) VALUES (?,?,1,'parcel.status.changed.v1',?::jsonb,?)",
                eventId, p.parcelId(), event, java.sql.Timestamp.from(p.createdAt()));
        jdbc.update("INSERT INTO idempotency_records (actor_sub,idempotency_key,request_digest,parcel_id,response_json,created_at) VALUES (?,?,?,?,?::jsonb,?)",
                p.createdBySub(), key, digest, p.parcelId(), json.writeValueAsString(p), java.sql.Timestamp.from(p.createdAt()));
    }

    public void finishMerchant(UUID parcelId,
                               String senderSub,
                               String principalOrTenant,
                               UUID key,
                               String digest,
                               UUID eventId,
                               String event,
                               String responseJson,
                               Instant createdAt) {
        Timestamp ts = Timestamp.from(createdAt);
        Timestamp expiresAt = Timestamp.from(createdAt.plus(Duration.ofDays(7)));

        jdbc.update("""
                INSERT INTO status_history (
                    history_id, parcel_id, previous_status, new_status, reason, note, proof_id, actor_sub, resulting_version, occurred_at
                ) VALUES (
                    ?, ?, NULL, 'CREATED', NULL, 'Initial merchant parcel booking', NULL, ?, 1, ?
                )
                """,
                UUID.randomUUID(), parcelId, senderSub, ts
        );

        jdbc.update("""
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, aggregate_version, event_type, schema_version, payload, status, topic, partition_key, created_at, next_attempt_at
                ) VALUES (
                    ?, 'PARCEL', ?, 1, 'parcel.status.changed.v1', '1.0.0', ?::jsonb, 'PENDING', 'parcel.lifecycle.v1', ?, ?, ?
                )
                """,
                eventId, parcelId, event, parcelId.toString(), ts, ts
        );

        jdbc.update("""
                INSERT INTO idempotency_records (
                    record_id, principal_or_tenant, idempotency_key, request_digest, status, stored_result, created_at, expires_at
                ) VALUES (
                    ?, ?, ?, ?, 'COMPLETED', ?::jsonb, ?, ?
                )
                """,
                UUID.randomUUID(), principalOrTenant, key.toString(), digest, responseJson, ts, expiresAt
        );
    }
}
