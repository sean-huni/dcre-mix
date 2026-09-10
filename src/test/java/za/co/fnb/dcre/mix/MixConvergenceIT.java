package za.co.fnb.dcre.mix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 v1 convergence proofs for {@code man_isr_resp}. Every DCRE database is dropped and
 * recreated for the direct cut-over, so there is no migrated database and no MAR-era table: what
 * these fixtures model is a SECOND CREATOR on a brand-new dcre_man, not history.
 *
 * <p>man_isr_resp has exactly two creators. MIX's own 001-man-isr-resp.xml, and MRG's bootstrap
 * pre-create in mrg 004-man-views.xml ({@code 004-bootstrap-man-isr-resp-mrg}), which mints the
 * table and the unique constraint together because MRG is clock-launched and may run before MIX
 * has ever executed. All ten M-services migrate the one dcre_man with ten independent history
 * tables and nothing serializes them, so either order is legal: whichever runs first creates, and
 * the other side's guarded changesets MARK_RAN.
 */
class MixConvergenceIT extends AbstractCrdbIT {

    /**
     * MRG's pre-create, byte-for-byte in shape: table AND unique constraint in one changeset.
     * This is the state MIX finds when MRG wins the race on a brand-new database.
     */
    private static final String MRG_PRECREATED_ISR_TABLE = """
            CREATE TABLE man_isr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(128) NOT NULL, orgnl_msg_id VARCHAR(35) NOT NULL,
              mndt_id VARCHAR(35) NOT NULL, mndt_req_id VARCHAR(35) NOT NULL,
              e2e VARCHAR(35), status VARCHAR(8) NOT NULL, reason VARCHAR(8),
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              CONSTRAINT uq_man_isr_resp_file_mndt_req UNIQUE (response_file, mndt_req_id))""";

    /**
     * A PARTIAL pre-create: the table stands, its unique constraint does not. No creator in the
     * fleet produces this today (MRG mints both in one changeset), so this fixture is not a
     * database state that exists anywhere. It is the red-proof of the guard SPLIT: with one
     * tableExists gate over both statements, MIX would MARK_RAN the whole changeset here and
     * leave the runtime {@code ON CONFLICT (response_file, mndt_req_id)} with no constraint to
     * arbitrate on, silently losing the zero-duplicate guarantee. Guarding the constraint on the
     * schema state IT transforms is what makes any partial pre-create converge.
     */
    private static final String PARTIAL_PRECREATE_TABLE_ONLY = """
            CREATE TABLE man_isr_resp (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              response_file VARCHAR(128) NOT NULL, orgnl_msg_id VARCHAR(35) NOT NULL,
              mndt_id VARCHAR(35) NOT NULL, mndt_req_id VARCHAR(35) NOT NULL,
              e2e VARCHAR(35), status VARCHAR(8) NOT NULL, reason VARCHAR(8),
              version BIGINT NOT NULL DEFAULT 0,
              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""";

    /** The runtime guarded write, byte-identical in shape to ManRespRepo.insertGuarded. */
    private static final String GUARDED_INSERT = """
            INSERT INTO man_isr_resp (id, response_file, orgnl_msg_id, mndt_id, mndt_req_id, status)
            VALUES (gen_random_uuid(), 'F1', 'OUT-1', 'MND-1', 'MREQ-1', 'ACCP')
            ON CONFLICT (response_file, mndt_req_id) DO NOTHING""";

    @Test
    void aPartialPreCreateGainsTheUniqueConstraintAndOnConflictStillArbitrates() throws Exception {
        jdbc.execute(PARTIAL_PRECREATE_TABLE_ONLY);

        runLiquibase();
        runLiquibase();

        assertThat(jdbc.update(GUARDED_INSERT)).isOne();
        assertThat(jdbc.update(GUARDED_INSERT))
                .as("ON CONFLICT (response_file, mndt_req_id) needs the constraint to arbitrate on")
                .isZero();

        assertThat(execTypeOf("mix-001-man-isr-resp")).isEqualTo("MARK_RAN");
        assertThat(execTypeOf("mix-001-man-isr-resp-uq"))
                .as("the constraint is guarded on its OWN schema state, so it still executes")
                .isEqualTo("EXECUTED");
        assertThat(countIndexesOn("man_isr_resp")).isEqualTo(2);
    }

    @Test
    void mrgPreCreatedTableAndConstraintBothConvergeToMarkRan() throws Exception {
        jdbc.execute(MRG_PRECREATED_ISR_TABLE);

        runLiquibase();
        runLiquibase();

        assertThat(execTypeOf("mix-001-man-isr-resp")).isEqualTo("MARK_RAN");
        assertThat(execTypeOf("mix-001-man-isr-resp-uq"))
                .as("the constraint already stands, so its own guard converges too")
                .isEqualTo("MARK_RAN");
        assertThat(countIndexesOn("man_isr_resp")).isEqualTo(2);

        assertThat(jdbc.update(GUARDED_INSERT)).isOne();
        assertThat(jdbc.update(GUARDED_INSERT))
                .as("MIX's runtime write must still be a zero-duplicate no-op on MRG's copy")
                .isZero();
    }

    @Test
    void mixWinningTheRaceExecutesBothChangesetsAndDoubleApplyIsANoOp() throws Exception {
        runLiquibase();
        runLiquibase();

        assertThat(execTypeOf("mix-001-man-isr-resp")).isEqualTo("EXECUTED");
        assertThat(execTypeOf("mix-001-man-isr-resp-uq")).isEqualTo("EXECUTED");
        assertThat(countIndexesOn("man_isr_resp")).isEqualTo(2);

        assertThat(jdbc.update(GUARDED_INSERT)).isOne();
        assertThat(jdbc.update(GUARDED_INSERT))
                .as("the v1 primary path must arbitrate ON CONFLICT exactly as a converged one does")
                .isZero();
    }

    @Test
    void mixShipsNeitherTheSbsrNorThePbsrTable() throws Exception {
        runLiquibase();

        assertThat(countIndexesOn("man_sbsr_resp")).isZero();
        assertThat(countIndexesOn("man_pbsr_resp")).isZero();
    }
}
