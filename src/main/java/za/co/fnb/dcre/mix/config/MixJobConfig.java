package za.co.fnb.dcre.mix.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mix.service.ReaderTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MIX job shape (single tasklet, CIX clone): readerStep ingests one pain.012 ISR
 * acceptance leg per launch into man_isr_resp. Identifying JobParameter:
 * arrival.id (R-16). SCRUM-91: there is NO reply.type launch arg, the leg is
 * fixed by the service (mirror of collections CIX). Runs on the default
 * SERIALIZABLE isolation (no READ COMMITTED override; only CRG carries RC per
 * SCRUM-90).
 */
@Configuration
public class MixJobConfig {

    @Bean
    public Job mixJob(final JobRepository repo, final PlatformTransactionManager tx, final ReaderTasklet tasklet,
                      final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        // CRDB 40001 retry on the ingest step (the one that WRITES): the response
        // upsert commits in its own REQUIRES_NEW tx inside ReaderService, so the
        // aborts hit the chunk-commit boundary, which only a step-level handler
        // sees. Retry, never skip. The step tx is THIN: a step-level re-run no-ops
        // over the committed identity.
        final Step readerStep = new StepBuilder("readerStep", repo).tasklet(tasklet, tx)
                .exceptionHandler(new CrdbRetryExceptionHandler("MIX")).build();
        // Shared platform-batch seam listener (COMPLETED-gated, constant
        // BUSINESS_ACCEPTED verdict; dev fallback local-mix-<executionId>) plus the
        // HeartbeatWriter liveness stamp on agt_ops while the job runs.
        return new JobBuilder("mixJob", repo)
                .listener(new OutcomeSeamListener("mix", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(readerStep)
                .build();
    }
}
