package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : SerialServiceTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Concurrent requests never receive the same document serial
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.SerialService;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SerialServiceTest extends IntegrationTest {

    @Autowired SerialService serials;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void twentyRequestsAtOnceGetTwentyDifferentSerialsInTheAgreedFormat() throws Exception {
        UUID rubavu = fx.branch("RBV");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Callable<String> task = () -> {
                go.await();
                return tx.execute(status -> serials.next("DMG", rubavu));
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        List<String> issued = new ArrayList<>();
        for (Future<String> f : results) issued.add(f.get());
        pool.shutdown();

        Set<String> distinct = issued.stream().collect(Collectors.toSet());
        assertThat(distinct).hasSize(20);
        assertThat(issued).allMatch(s -> s.matches("DMG-RBV-\\d{4}-\\d{4}"));
    }

    @Test
    void aNewYearStartsItsOwnSequenceAtOne() {
        // A branch and type with no row for this year gets one, numbered from 0001.
        jdbc.sql("DELETE FROM serial_sequence WHERE year = EXTRACT(YEAR FROM kigali_today())::int "
                + "AND document_type_id = (SELECT id FROM document_type WHERE code = 'VR') "
                + "AND branch_id = (SELECT id FROM branch WHERE code = 'RBV')").update();
        String first = new TransactionTemplate(transactions)
                .execute(status -> serials.next("VR", fx.branch("RBV")));
        String second = new TransactionTemplate(transactions)
                .execute(status -> serials.next("VR", fx.branch("RBV")));
        assertThat(first).endsWith("-0001");
        assertThat(second).endsWith("-0002");
    }
}
