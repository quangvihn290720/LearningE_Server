package com.learninge.server.sync.store;

import com.learninge.proto.app.v1.Change;
import com.learninge.proto.app.v1.WordData;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bảng {@code words}: từ do người dùng tạo.
 */
public final class WordTable extends SyncTable {
    WordTable() {
        super("words");
    }

    public void upsert(
            JdbcClient writer,
            String userId,
            String uuid,
            WordData data,
            boolean deleted,
            long updatedAt,
            long seq) {
        writer
                .sql(
                        """
                                INSERT INTO words (uuid, user_id, deck_uuid, term, reading, part_of_speech, meaning_vi,
                                                   example, example_translation, sort_order, created_at, updated_at,
                                                   deleted, server_seq)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT (uuid) DO UPDATE SET deck_uuid = excluded.deck_uuid, term = excluded.term,
                                  reading = excluded.reading, part_of_speech = excluded.part_of_speech,
                                  meaning_vi = excluded.meaning_vi, example = excluded.example,
                                  example_translation = excluded.example_translation,
                                  sort_order = excluded.sort_order, updated_at = excluded.updated_at,
                                  deleted = excluded.deleted, server_seq = excluded.server_seq
                                WHERE words.user_id = excluded.user_id
                                """)
                .params(
                        uuid,
                        userId,
                        data.getDeckUuid(),
                        data.getTerm(),
                        data.hasReading() ? data.getReading() : null,
                        data.hasPartOfSpeech() ? data.getPartOfSpeech() : null,
                        data.getMeaningVi(),
                        data.hasExample() ? data.getExample() : null,
                        data.hasExampleTranslation() ? data.getExampleTranslation() : null,
                        data.getSortOrder(),
                        data.getCreatedAtMs(),
                        updatedAt,
                        deleted ? 1 : 0,
                        seq)
                .update();
    }

    @Override
    protected RowMapper<Change> mapper() {
        return (rs, i) -> {
            WordData.Builder data =
                    WordData.newBuilder()
                            .setDeckUuid(rs.getString("deck_uuid"))
                            .setTerm(rs.getString("term"))
                            .setMeaningVi(rs.getString("meaning_vi"))
                            .setSortOrder(rs.getInt("sort_order"))
                            .setCreatedAtMs(rs.getLong("created_at"));
            Columns.text(rs, "reading", data::setReading);
            Columns.text(rs, "part_of_speech", data::setPartOfSpeech);
            Columns.text(rs, "example", data::setExample);
            Columns.text(rs, "example_translation", data::setExampleTranslation);
            return Change.newBuilder()
                    .setRecordId(rs.getString("uuid"))
                    .setUpdatedAtMs(rs.getLong("updated_at"))
                    .setDeleted(rs.getBoolean("deleted"))
                    .setServerSeq(rs.getLong("server_seq"))
                    .setWord(data)
                    .build();
        };
    }
}
