/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.postgresql.codec;

import io.r2dbc.postgresql.client.EncodedParameter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static io.r2dbc.postgresql.client.EncodedParameter.NULL_VALUE;
import static io.r2dbc.postgresql.client.ParameterAssert.assertThat;
import static io.r2dbc.postgresql.codec.PostgresqlObjectId.DATE;
import static io.r2dbc.postgresql.codec.PostgresqlObjectId.MONEY;
import static io.r2dbc.postgresql.codec.PostgresqlObjectId.POLARDB_DATE;
import static io.r2dbc.postgresql.codec.PostgresqlObjectId.TIMESTAMP;
import static io.r2dbc.postgresql.codec.PostgresqlObjectId.VARCHAR;
import static io.r2dbc.postgresql.message.Format.FORMAT_BINARY;
import static io.r2dbc.postgresql.message.Format.FORMAT_TEXT;
import static io.r2dbc.postgresql.util.ByteBufUtils.encode;
import static io.r2dbc.postgresql.util.TestByteBufAllocator.TEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link LocalDateCodec}.
 */
final class LocalDateCodecUnitTests {

    private static final int dataType = DATE.getObjectId();

    @Test
    void constructorNoByteBufAllocator() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalDateCodec(null))
            .withMessage("byteBufAllocator must not be null");
    }

    @Test
    void decode() {
        LocalDate localDate = LocalDate.now();

        assertThat(new LocalDateCodec(TEST).decode(encode(TEST, localDate.toString()), dataType, FORMAT_TEXT, LocalDate.class))
            .isEqualTo(localDate);
    }

    @Test
    void decodeNoByteBuf() {
        assertThat(new LocalDateCodec(TEST).decode(null, dataType, FORMAT_TEXT, LocalDate.class)).isNull();
    }

    @Test
    void doCanDecode() {
        LocalDateCodec codec = new LocalDateCodec(TEST);

        assertThat(codec.doCanDecode(DATE, FORMAT_BINARY)).isTrue();
        assertThat(codec.doCanDecode(MONEY, FORMAT_TEXT)).isFalse();
        assertThat(codec.doCanDecode(DATE, FORMAT_TEXT)).isTrue();
    }

    @Test
    void doCanDecodeNoFormat() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalDateCodec(TEST).doCanDecode(VARCHAR, null))
            .withMessage("format must not be null");
    }

    @Test
    void doCanDecodeNoType() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalDateCodec(TEST).doCanDecode(null, FORMAT_TEXT))
            .withMessage("type must not be null");
    }

    @Test
    void doEncode() {
        LocalDate localDate = LocalDate.now();

        assertThat(new LocalDateCodec(TEST).doEncode(localDate))
            .hasFormat(FORMAT_TEXT)
            .hasType(DATE.getObjectId())
            .hasValue(encode(TEST, localDate.toString()));
    }

    @Test
    void doEncodeNoValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalDateCodec(TEST).doEncode(null))
            .withMessage("value must not be null");
    }

    @Test
    void encodeNull() {
        assertThat(new LocalDateCodec(TEST).encodeNull())
            .isEqualTo(new EncodedParameter(FORMAT_TEXT, DATE.getObjectId(), NULL_VALUE));
    }

    // PolarDB date type tests

    @Test
    void doCanDecodePolarDbDate() {
        LocalDateCodec codec = new LocalDateCodec(TEST, true);

        assertThat(codec.doCanDecode(POLARDB_DATE, FORMAT_TEXT)).isTrue();
        assertThat(codec.doCanDecode(POLARDB_DATE, FORMAT_BINARY)).isTrue();
        assertThat(codec.doCanDecode(DATE, FORMAT_TEXT)).isTrue();
        assertThat(codec.doCanDecode(TIMESTAMP, FORMAT_TEXT)).isTrue();
    }

    @Test
    void doEncodePolarDbDate() {
        LocalDate localDate = LocalDate.of(2024, 3, 24);

        assertThat(new LocalDateCodec(TEST, true).doEncode(localDate))
            .hasFormat(FORMAT_TEXT)
            .hasType(POLARDB_DATE.getObjectId())
            .hasValue(encode(TEST, "2024-03-24 00:00:00"));
    }

    @Test
    void encodeNullPolarDbDate() {
        assertThat(new LocalDateCodec(TEST, true).encodeNull())
            .isEqualTo(new EncodedParameter(FORMAT_TEXT, POLARDB_DATE.getObjectId(), NULL_VALUE));
    }

    @Test
    void decodePolarDbDateText() {
        // PolarDB date type returns data in timestamp format
        LocalDate expected = LocalDate.of(2024, 3, 24);
        LocalDateCodec codec = new LocalDateCodec(TEST, true);

        assertThat(codec.decode(encode(TEST, "2024-03-24 00:00:00"), POLARDB_DATE.getObjectId(), FORMAT_TEXT, LocalDate.class))
            .isEqualTo(expected);
    }

    @Test
    void decodePolarDbDateTextWithTime() {
        // PolarDB date with non-midnight time should still produce correct date
        LocalDate expected = LocalDate.of(2024, 3, 24);
        LocalDateCodec codec = new LocalDateCodec(TEST, true);

        assertThat(codec.decode(encode(TEST, "2024-03-24 15:30:00"), POLARDB_DATE.getObjectId(), FORMAT_TEXT, LocalDate.class))
            .isEqualTo(expected);
    }

    @Test
    void standardCodecDoesNotUsePolarDbOid() {
        // Standard LocalDateCodec should use DATE OID (1082), not POLARDB_DATE (9002)
        LocalDate localDate = LocalDate.of(2024, 3, 24);

        assertThat(new LocalDateCodec(TEST, false).doEncode(localDate))
            .hasFormat(FORMAT_TEXT)
            .hasType(DATE.getObjectId())
            .hasValue(encode(TEST, "2024-03-24"));
    }

}
