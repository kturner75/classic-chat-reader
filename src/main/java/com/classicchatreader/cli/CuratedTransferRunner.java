package com.classicchatreader.cli;

import com.classicchatreader.curated.CuratedTransfer;
import java.io.PrintStream;

/** One title's curated catalog membership export/replace for Studio production listing (BL-072.4). */
public final class CuratedTransferRunner {
    private static final BookTransferCli.Transfer<CuratedTransfer.Plan, CuratedTransfer.Snapshot> TRANSFER =
            new BookTransferCli.Transfer<>("Curated membership", "curated-", CuratedTransfer.Plan.class,
                    CuratedTransfer.Plan::source, CuratedTransfer.Plan::sourceId,
                    CuratedTransfer::exportMembership, CuratedTransfer::apply, CuratedTransfer.StalePlan.class);
    private CuratedTransferRunner() {}
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }
    static int run(String[] args, PrintStream out, PrintStream err) {
        return BookTransferCli.run(args, out, err, TRANSFER);
    }
}
