package com.classicchatreader.cli;

import com.classicchatreader.roster.RosterTransfer;
import java.io.PrintStream;

/** Deliberately separate from portrait/cache transfer. Never touches image files or Spaces. */
public final class RosterTransferRunner {
    private static final BookTransferCli.Transfer<RosterTransfer.Plan, RosterTransfer.Snapshot> TRANSFER =
            new BookTransferCli.Transfer<>("Roster", "roster-", RosterTransfer.Plan.class,
                    RosterTransfer.Plan::source, RosterTransfer.Plan::sourceId,
                    RosterTransfer::exportRoster, RosterTransfer::apply, RosterTransfer.StalePlan.class);
    private RosterTransferRunner() {}
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }
    static int run(String[] args, PrintStream out, PrintStream err) {
        return BookTransferCli.run(args, out, err, TRANSFER);
    }
}
