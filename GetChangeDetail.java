// Read a change with detail (GET /changes/{id} -> ChangeInfo, anonymous) and print a
// colored, Web-UI-style summary using Gerrit's own palette -- the Java twin of the Rust,
// Go, Python, and TypeScript examples. The SDK is fetched from JitPack via Bazel
// (rules_jvm_external); every value comes from the generated gerrit-sdk-java models.
//
// Gerrit's )]}' XSSI guard is stripped by GerritXssiInterceptor (an OkHttp Interceptor)
// wired in by GerritXssiInterceptor.newClient(...) -- the one Gerrit-specific step.
import com.google.gerrit.client.ApiException;
import com.google.gerrit.client.GerritXssiInterceptor;
import com.google.gerrit.client.api.ChangesApi;
import com.google.gerrit.client.model.AccountInfo;
import com.google.gerrit.client.model.ApprovalInfo;
import com.google.gerrit.client.model.ChangeInfo;
import com.google.gerrit.client.model.CommitInfo;
import com.google.gerrit.client.model.CommonFileInfo;
import com.google.gerrit.client.model.GitPerson;
import com.google.gerrit.client.model.LabelInfo;
import com.google.gerrit.client.model.RevisionInfo;
import com.google.gerrit.client.model.SubmitRequirementResultInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class GetChangeDetail {
  static final List<String> OPTIONS =
      List.of(
          "LABELS",
          "DETAILED_ACCOUNTS",
          "DETAILED_LABELS",
          "CURRENT_REVISION",
          "CURRENT_COMMIT",
          "CURRENT_FILES",
          "SUBMIT_REQUIREMENTS");

  static boolean useColor;

  public static void main(String[] args) throws Exception {
    String url = "https://gerrit-review.googlesource.com";
    String change = "621763";
    boolean noColor = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--url" -> url = args[++i];
        case "--change" -> change = args[++i];
        case "--no-color" -> noColor = true;
        default -> {}
      }
    }
    useColor =
        !noColor
            && System.getenv("NO_COLOR") == null
            && (System.getenv("CLICOLOR_FORCE") != null || System.console() != null);

    String base = url.replaceAll("/+$", "");
    ChangesApi api = new ChangesApi(GerritXssiInterceptor.newClient(base));
    try {
      ChangeInfo ci = api.getChangesChangeId(change, null, null, OPTIONS);
      print(base, ci);
    } catch (ApiException e) {
      System.err.println("error: " + e.getMessage());
      System.exit(1);
    }
  }

  static void print(String base, ChangeInfo ci) {
    System.out.println(rule());
    System.out.println("  " + statusBadge(ci) + "  " + sgr("#" + nz(ci.getNumber()), BOLD));
    System.out.println("  " + sgr(orEmpty(ci.getSubject()), BOLD));
    System.out.println(rule());
    System.out.println(
        "  " + fg(base + "/c/" + orEmpty(ci.getProject()) + "/+/" + nz(ci.getNumber()), BLUE_700));

    section("Change Info");
    row("Owner", account(ci.getOwner()));
    CommitInfo commit = currentCommit(ci);
    if (commit != null) {
      row("Author", person(commit.getAuthor()));
      row("Committer", person(commit.getCommitter()));
    }
    row("Repo | Branch", link(orEmpty(ci.getProject())) + " | " + link(orEmpty(ci.getBranch())));
    row("Change-Id", link(orEmpty(ci.getChangeId())));
    if (notEmpty(ci.getTopic())) row("Topic", link(ci.getTopic()));
    if (ci.getHashtags() != null && !ci.getHashtags().isEmpty())
      row("Hashtags", link(String.join(", ", ci.getHashtags())));
    List<String> flags = flagChips(ci);
    if (!flags.isEmpty()) row("Flags", String.join("  ", flags));
    row("Strategy", ci.getSubmitType() == null ? "" : pascal(ci.getSubmitType().getValue()));
    String parent = parentCommit(ci);
    if (!parent.isEmpty()) row("Parent", link(parent.substring(0, Math.min(12, parent.length()))));
    row(
        "Patch set",
        ci.getCurrentRevisionNumber() == null
            ? "?"
            : String.valueOf(ci.getCurrentRevisionNumber()));
    row("Updated", orEmpty(ci.getUpdated()));
    row("Size", plusminus(nz(ci.getInsertions()), nz(ci.getDeletions())));
    row("Comments", commentsSummary(ci));

    Map<String, List<AccountInfo>> reviewers = ci.getReviewers();
    if (reviewers != null) {
      for (String[] kt : new String[][] {{"REVIEWER", "Reviewers"}, {"CC", "CC"}}) {
        List<AccountInfo> people = reviewers.get(kt[0]);
        if (people == null || people.isEmpty()) continue;
        section(kt[1]);
        for (AccountInfo a : people) System.out.println("    " + account(a));
      }
    }

    if (ci.getSubmitRequirements() != null && !ci.getSubmitRequirements().isEmpty()) {
      section("Submit Requirements");
      for (SubmitRequirementResultInfo r : ci.getSubmitRequirements()) {
        String status = r.getStatus() == null ? "" : r.getStatus().getValue();
        String[] p = reqParts(status);
        System.out.println("    " + p[0] + " " + pad(orEmpty(r.getName()), 26) + " " + p[1]);
      }
    }

    Map<String, LabelInfo> labels = ci.getLabels();
    if (labels != null && !labels.isEmpty()) {
      section("Votes");
      List<String> names = new ArrayList<>(labels.keySet());
      names.sort(Comparator.naturalOrder());
      for (String name : names) {
        List<String> chips = new ArrayList<>();
        List<ApprovalInfo> all = labels.get(name).getAll();
        if (all != null) {
          for (ApprovalInfo a : all) {
            int v = nz(a.getValue());
            if (v != 0) chips.add(voteChip(v, orEmpty(a.getName())));
          }
        }
        System.out.println(
            "    "
                + pad(name, 22)
                + " "
                + (chips.isEmpty() ? sgr("—", DIM) : String.join("  ", chips)));
      }
    }

    Map<String, CommonFileInfo> files = currentFiles(ci);
    if (files != null && !files.isEmpty()) {
      section(
          "Files (patch set "
              + (ci.getCurrentRevisionNumber() == null ? "?" : ci.getCurrentRevisionNumber())
              + ")");
      List<String> paths = new ArrayList<>(files.keySet());
      paths.sort(
          Comparator.comparing((String p) -> p.equals("/COMMIT_MSG") ? 0 : 1)
              .thenComparing(p -> p.toLowerCase()));
      for (String p : paths) {
        CommonFileInfo f = files.get(p);
        String[] fs = fileStatus(f.getStatus());
        String name =
            p.equals("/COMMIT_MSG")
                ? "Commit message"
                : notEmpty(f.getOldPath()) ? f.getOldPath() + " → " + p : p;
        System.out.println(
            "    "
                + fg(fs[0], parseRgb(fs[1]))
                + " "
                + pad(name, 52)
                + " "
                + plusminus(nz(f.getLinesInserted()), nz(f.getLinesDeleted())));
      }
    }
    System.out.println(rule());
  }

  // ---- model accessors --------------------------------------------------------

  static CommitInfo currentCommit(ChangeInfo ci) {
    String cr = ci.getCurrentRevision();
    Map<String, RevisionInfo> revs = ci.getRevisions();
    if (cr == null || revs == null || revs.get(cr) == null) return null;
    return revs.get(cr).getCommit();
  }

  static Map<String, CommonFileInfo> currentFiles(ChangeInfo ci) {
    String cr = ci.getCurrentRevision();
    Map<String, RevisionInfo> revs = ci.getRevisions();
    if (cr == null || revs == null || revs.get(cr) == null) return null;
    return revs.get(cr).getFiles();
  }

  static String parentCommit(ChangeInfo ci) {
    CommitInfo commit = currentCommit(ci);
    if (commit == null || commit.getParents() == null || commit.getParents().isEmpty()) return "";
    return orEmpty(commit.getParents().get(0).getCommit());
  }

  static String account(AccountInfo a) {
    if (a == null) return "—";
    if (notEmpty(a.getName()) && notEmpty(a.getEmail())) return named(a.getName(), a.getEmail());
    if (notEmpty(a.getName())) return sgr(a.getName(), BOLD);
    return a.getAccountId() != null ? "account #" + a.getAccountId() : "—";
  }

  static String person(GitPerson p) {
    if (p == null || (!notEmpty(p.getName()) && !notEmpty(p.getEmail()))) return "—";
    return named(orEmpty(p.getName()), orEmpty(p.getEmail()));
  }

  // Bold name, dim <email>. No blue -- reserve blue for links.
  static String named(String name, String email) {
    return sgr(name, BOLD) + " " + sgr("<" + email + ">", DIM);
  }

  static List<String> flagChips(ChangeInfo ci) {
    List<String> f = new ArrayList<>();
    if (Boolean.TRUE.equals(ci.getWorkInProgress())) f.add(chip(" WIP ", WHITE, WIP_BROWN));
    if (Boolean.TRUE.equals(ci.getIsPrivate())) f.add(chip(" Private ", WHITE, PURPLE_500));
    if (Boolean.TRUE.equals(ci.getMergeable())) f.add(fg("mergeable", GREEN_700));
    if (Boolean.TRUE.equals(ci.getSubmittable())) f.add(fg("submittable", GREEN_700));
    return f;
  }

  static String commentsSummary(ChangeInfo ci) {
    int total = nz(ci.getTotalCommentCount());
    int unresolved = nz(ci.getUnresolvedCommentCount());
    int resolved = Math.max(total - unresolved, 0);
    int[] openColor = unresolved > 0 ? RED_600 : GREEN_700;
    return total
        + " total  ("
        + fg(resolved + " resolved", GREEN_700)
        + ", "
        + fg(unresolved + " unresolved", openColor)
        + ")";
  }

  static String pascal(String s) {
    StringBuilder b = new StringBuilder();
    for (String part : s.split("_")) {
      if (!part.isEmpty())
        b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
    }
    return b.toString();
  }

  // ---- styling ----------------------------------------------------------------

  static final String BOLD = "1", DIM = "2";
  static final int[] WHITE = {255, 255, 255}, BLACK = {0, 0, 0};
  static final int[] GRAY_700 = {95, 99, 104},
      YELLOW_700 = {242, 153, 0},
      WIP_BROWN = {121, 85, 72};
  static final int[] PURPLE_500 = {161, 66, 244},
      GREEN_700 = {24, 128, 56},
      GREEN_300 = {129, 201, 149};
  static final int[] RED_300 = {242, 139, 130},
      RED_600 = {217, 48, 37},
      BLUE_700 = {25, 103, 210},
      HEADER_INDIGO = {62, 78, 138};

  static String sgr(String s, String code) {
    return useColor ? "[" + code + "m" + s + "[0m" : s;
  }

  static String fg(String s, int[] c) {
    return useColor ? "[38;2;" + c[0] + ";" + c[1] + ";" + c[2] + "m" + s + "[0m" : s;
  }

  static String chip(String s, int[] f, int[] b) {
    return useColor
        ? "[38;2;" + f[0] + ";" + f[1] + ";" + f[2] + ";48;2;" + b[0] + ";" + b[1] + ";" + b[2]
            + "m" + s + "[0m"
        : s;
  }

  static String link(String s) {
    return fg(s, BLUE_700);
  }

  static String rule() {
    return fg("─".repeat(76), HEADER_INDIGO);
  }

  static void section(String title) {
    System.out.println();
    System.out.println("  " + sgr(title.toUpperCase(), BOLD));
  }

  static void row(String label, String value) {
    System.out.println("    " + sgr(pad(label, 14), DIM) + value);
  }

  static String statusBadge(ChangeInfo ci) {
    String raw = ci.getStatus() == null ? "" : ci.getStatus().getValue().toUpperCase();
    String label;
    int[] fgc, bg;
    if (raw.equals("MERGED")) {
      label = "Merged";
      fgc = WHITE;
      bg = GRAY_700;
    } else if (raw.equals("ABANDONED")) {
      label = "Abandoned";
      fgc = WHITE;
      bg = GRAY_700;
    } else if (Boolean.TRUE.equals(ci.getWorkInProgress())) {
      label = "WIP";
      fgc = WHITE;
      bg = WIP_BROWN;
    } else if (Boolean.TRUE.equals(ci.getIsPrivate())) {
      label = "Private";
      fgc = WHITE;
      bg = PURPLE_500;
    } else {
      label = "Active";
      fgc = BLACK;
      bg = YELLOW_700;
    }
    return chip(" " + label + " ", fgc, bg);
  }

  static String voteChip(int v, String who) {
    int[] bg = v > 0 ? GREEN_300 : RED_300;
    return chip(" " + (v > 0 ? "+" + v : String.valueOf(v)) + " ", BLACK, bg) + " " + who;
  }

  static String plusminus(int ins, int del) {
    return fg("+" + ins, GREEN_700) + " " + fg("-" + del, RED_600);
  }

  static String[] reqParts(String status) {
    String display = status.isEmpty() ? "" : pascal(status);
    if (status.equals("SATISFIED"))
      return new String[] {fg("✓", GREEN_700), fg(display, GREEN_700)};
    if (status.equals("UNSATISFIED")) return new String[] {fg("✗", RED_600), fg(display, RED_600)};
    return new String[] {sgr("○", DIM), sgr(display, DIM)};
  }

  static String[] fileStatus(String s) {
    if (s == null) return new String[] {"M", rgbStr(GRAY_700)};
    return switch (s) {
      case "A" -> new String[] {"A", rgbStr(GREEN_700)};
      case "D" -> new String[] {"D", rgbStr(RED_600)};
      case "R" -> new String[] {"R", rgbStr(BLUE_700)};
      case "C" -> new String[] {"C", rgbStr(BLUE_700)};
      case "W" -> new String[] {"W", rgbStr(PURPLE_500)};
      default -> new String[] {"M", rgbStr(GRAY_700)};
    };
  }

  // ---- small helpers ----------------------------------------------------------

  static int nz(Integer i) {
    return i == null ? 0 : i;
  }

  static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  static boolean notEmpty(String s) {
    return s != null && !s.isEmpty();
  }

  static String pad(String s, int w) {
    return s.length() >= w ? s : s + " ".repeat(w - s.length());
  }

  static String rgbStr(int[] c) {
    return c[0] + "," + c[1] + "," + c[2];
  }

  static int[] parseRgb(String s) {
    String[] p = s.split(",");
    return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
  }
}
