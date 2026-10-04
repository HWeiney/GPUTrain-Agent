package com.ruoyi.train.runner.fixture;

public final class FixtureMain
{
    private FixtureMain() { }
    public static void main(String[] args) throws InterruptedException
    {
        if (args.length == 2 && args[0].equals("--mode"))
        {
            if (args[1].equals("success")) { System.out.println("fixture success"); return; }
            if (args[1].equals("failure")) { System.err.println("fixture failure"); System.exit(17); }
        }
        if (args.length == 4 && args[0].equals("--mode") && args[1].equals("sleep") && args[2].equals("--seconds"))
        {
            try
            {
                long seconds = Long.parseLong(args[3]);
                if (seconds < 0 || seconds > 86400) throw new NumberFormatException();
                System.out.println(ProcessHandle.current().pid()); System.out.flush();
                Thread.sleep(seconds * 1000); return;
            }
            catch (NumberFormatException ignored) { }
        }
        System.err.println("Usage: --mode success|failure OR --mode sleep --seconds N (0..86400)");
        System.exit(2);
    }
}
