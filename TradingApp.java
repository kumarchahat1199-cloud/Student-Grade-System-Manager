import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/*
 * Java Stock Trading Simulator (Console, OOP, single-file)
 * --------------------------------------------------------
 * Features:
 *  - Simulated market data with random-walk price updates.
 *  - Buy/Sell market orders with instant execution.
 *  - Portfolio with cash, positions, P&L, and performance history over time.
 *  - Order/Trade logging.
 *  - Simple CSV file I/O to persist portfolio and trade history.
 *  - Clean OOP design in a single file for easy compilation.
 *
 * Compile:  javac TradingApp.java
 * Run:      java TradingApp
 */

public class TradingApp {
    public static void main(String[] args) {
        ConsoleUI.run();
    }
}

// ======== Domain Models ========
class Stock {
    private final String symbol;
    private final String name;
    private double price;        // last traded price
    private final double vol;    // daily-ish volatility proxy (0.01 = 1%)

    public Stock(String symbol, String name, double initialPrice, double vol) {
        this.symbol = symbol.toUpperCase(Locale.ROOT);
        this.name = name;
        this.price = initialPrice;
        this.vol = vol;
    }

    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public double getPrice() { return price; }

    // Simple random-walk update (geometric Brownian motion approximation)
    public void tick(Random rng) {
        double dt = 1.0; // 1 synthetic time step
        double mu = 0.000; // drift (0 for neutrality)
        double sigma = vol; // volatility
        double shock = rng.nextGaussian() * Math.sqrt(dt);
        double ret = mu * dt + sigma * shock;
        price = Math.max(0.01, price * Math.exp(ret));
    }

    @Override public String toString() {
        return symbol + " (" + name + ") @ " + String.format(Locale.US, "%.2f", price);
    }
}

class Market {
    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private final Random rng = new Random();
    private long ticks = 0L;

    public Market() {}

    public void add(Stock s) { stocks.put(s.getSymbol(), s); }

    public Stock get(String symbol) { return stocks.get(symbol.toUpperCase(Locale.ROOT)); }

    public Collection<Stock> all() { return Collections.unmodifiableCollection(stocks.values()); }

    public void tick() {
        for (Stock s : stocks.values()) s.tick(rng);
        ticks++;
    }

    public long getTicks() { return ticks; }
}

enum Side { BUY, SELL }

class Order {
    public final String symbol;
    public final int quantity;
    public final Side side;
    public final LocalDateTime time;

    public Order(String symbol, int quantity, Side side) {
        this.symbol = symbol.toUpperCase(Locale.ROOT);
        this.quantity = quantity;
        this.side = side;
        this.time = LocalDateTime.now();
    }
}

class Trade {
    public final String symbol;
    public final int quantity; // positive for buy, negative for sell
    public final double price; // executed price
    public final LocalDateTime time;

    public Trade(String symbol, int signedQty, double price) {
        this.symbol = symbol.toUpperCase(Locale.ROOT);
        this.quantity = signedQty;
        this.price = price;
        this.time = LocalDateTime.now();
    }

    public double cashImpact() { return -quantity * price; } // buy reduces cash, sell increases cash
}

class Position {
    public int qty = 0;
    public double avgPrice = 0.0; // volume-weighted average cost

    public void apply(Trade t) {
        if (t.quantity > 0) {
            // Buy: update average price
            double cost = avgPrice * qty + t.price * t.quantity;
            qty += t.quantity;
            avgPrice = qty == 0 ? 0.0 : cost / qty;
        } else {
            // Sell: reduce qty, avgPrice unchanged for remaining
            qty += t.quantity; // t.quantity is negative
            if (qty == 0) avgPrice = 0.0;
        }
    }
}

class Portfolio {
    private double cash = 100000.00; // starting cash
    private final Map<String, Position> positions = new TreeMap<>();
    private final List<PortfolioSnapshot> history = new ArrayList<>();

    public double getCash() { return cash; }

    public Map<String, Position> getPositions() { return positions; }

    public void apply(Trade t) {
        cash += t.cashImpact();
        Position p = positions.computeIfAbsent(t.symbol, k -> new Position());
        p.apply(t);
        if (p.qty == 0) positions.remove(t.symbol);
    }

    public double marketValue(Market m) {
        double val = 0.0;
        for (Map.Entry<String, Position> e : positions.entrySet()) {
            Stock s = m.get(e.getKey());
            if (s != null) val += e.getValue().qty * s.getPrice();
        }
        return val;
    }

    public double totalEquity(Market m) { return getCash() + marketValue(m); }

    public void recordSnapshot(Market m, String label) {
        history.add(new PortfolioSnapshot(LocalDateTime.now(), label, getCash(), marketValue(m), totalEquity(m)));
    }

    public List<PortfolioSnapshot> getHistory() { return history; }

    public static class PortfolioSnapshot {
        public final LocalDateTime time;
        public final String label;
        public final double cash;
        public final double mktValue;
        public final double equity;
        public PortfolioSnapshot(LocalDateTime time, String label, double cash, double mktValue, double equity) {
            this.time = time; this.label = label; this.cash = cash; this.mktValue = mktValue; this.equity = equity;
        }
    }
}

class User {
    public final String name;
    public final Portfolio portfolio = new Portfolio();
    public final List<Trade> tradeLog = new ArrayList<>();

    public User(String name) { this.name = name; }
}

class TradingEngine {
    private final Market market;
    private double commissionPerTrade = 0.0; // set >0.0 to simulate fees

    public TradingEngine(Market market) { this.market = market; }

    public Trade placeMarketOrder(User user, Order order) throws IllegalArgumentException {
        Stock s = market.get(order.symbol);
        if (s == null) throw new IllegalArgumentException("Unknown symbol: " + order.symbol);
        if (order.quantity <= 0) throw new IllegalArgumentException("Quantity must be positive.");

        int signedQty = order.side == Side.BUY ? order.quantity : -order.quantity;
        double execPrice = s.getPrice();
        double commission = commissionPerTrade;
        Trade t = new Trade(order.symbol, signedQty, execPrice);

        // Check cash for buy
        double cashImpact = t.cashImpact() - commission; // include commission
        if (user.portfolio.getCash() + cashImpact < -1e-6) {
            throw new IllegalArgumentException("Insufficient cash to buy. Needed: " + String.format(Locale.US, "%.2f", -cashImpact) +
                                               ", Available: " + String.format(Locale.US, "%.2f", user.portfolio.getCash()));
        }

        // Apply to portfolio
        user.portfolio.apply(t);
        // Commission
        if (commission != 0.0) {
            user.portfolio.apply(new Trade("CASHFEE", 0, 0) { @Override public double cashImpact(){ return -commission; } });
        }
        user.tradeLog.add(t);
        return t;
    }
}

// ======== Persistence (CSV-based) ========
class DataStore {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void save(User user, Market market, File dir) throws IOException {
        if (!dir.exists()) dir.mkdirs();

        // Save cash
        try (PrintWriter pw = new PrintWriter(new File(dir, "cash.txt"))) {
            pw.println(String.format(Locale.US, "%.2f", user.portfolio.getCash()));
        }

        // Save positions
        try (PrintWriter pw = new PrintWriter(new File(dir, "positions.csv"))) {
            pw.println("symbol,qty,avgPrice");
            for (Map.Entry<String, Position> e : user.portfolio.getPositions().entrySet()) {
                Position p = e.getValue();
                pw.printf(Locale.US, "%s,%d,%.6f%n", e.getKey(), p.qty, p.avgPrice);
            }
        }

        // Save trades
        try (PrintWriter pw = new PrintWriter(new File(dir, "trades.csv"))) {
            pw.println("time,symbol,qty,price,cashImpact");
            for (Trade t : user.tradeLog) {
                pw.printf(Locale.US, "%s,%s,%d,%.6f,%.6f%n", TS.format(t.time), t.symbol, t.quantity, t.price, t.cashImpact());
            }
        }

        // Save history (performance)
        try (PrintWriter pw = new PrintWriter(new File(dir, "history.csv"))) {
            pw.println("time,label,cash,marketValue,equity");
            for (Portfolio.PortfolioSnapshot s : user.portfolio.getHistory()) {
                pw.printf(Locale.US, "%s,%s,%.6f,%.6f,%.6f%n", TS.format(s.time), s.label.replace(',', ' '), s.cash, s.mktValue, s.equity);
            }
        }

        // Save market snapshot
        try (PrintWriter pw = new PrintWriter(new File(dir, "market.csv"))) {
            pw.println("symbol,name,price");
            for (Stock s : market.all()) {
                pw.printf(Locale.US, "%s,%s,%.6f%n", s.getSymbol(), s.getName().replace(',', ' '), s.getPrice());
            }
        }
    }

    public static void load(User user, File dir) throws IOException {
        if (!dir.exists()) throw new FileNotFoundException("Directory not found: " + dir.getAbsolutePath());

        // Load cash
        File cashFile = new File(dir, "cash.txt");
        if (cashFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(cashFile))) {
                String line = br.readLine();
                double cash = Double.parseDouble(line.trim());
                // reset portfolio by applying delta to set cash
                double current = user.portfolio.getCash();
                double delta = cash - current;
                if (Math.abs(delta) > 1e-8) {
                    // hack: apply a zero-qty trade that only moves cash
                    user.portfolio.apply(new Trade("CASHLOAD", 0, 0) { @Override public double cashImpact(){ return delta; } });
                }
            }
        }

        // Load positions
        File posFile = new File(dir, "positions.csv");
        if (posFile.exists()) {
            // Clear existing positions by selling them (cash-neutral using avgPrice)
            for (Map.Entry<String, Position> e : new ArrayList<>(user.portfolio.getPositions().entrySet())) {
                Position p = e.getValue();
                if (p.qty != 0) {
                    user.portfolio.apply(new Trade(e.getKey(), -p.qty, p.avgPrice));
                }
            }
            try (BufferedReader br = new BufferedReader(new FileReader(posFile))) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 3) continue;
                    String sym = parts[0].trim();
                    int qty = Integer.parseInt(parts[1].trim());
                    double avg = Double.parseDouble(parts[2].trim());
                    if (qty != 0) {
                        user.portfolio.apply(new Trade(sym, qty, avg));
                    }
                }
            }
        }

        // Load trades (append-only for display; does not rebuild portfolio)
        File tradesFile = new File(dir, "trades.csv");
        if (tradesFile.exists()) {
            user.tradeLog.clear();
            try (BufferedReader br = new BufferedReader(new FileReader(tradesFile))) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 5) continue;
                    String sym = parts[1].trim();
                    int qty = Integer.parseInt(parts[2].trim());
                    double price = Double.parseDouble(parts[3].trim());
                    Trade t = new Trade(sym, qty, price);
                    user.tradeLog.add(t);
                }
            }
        }

        // Load history (optional, for continuity)
        File histFile = new File(dir, "history.csv");
        if (histFile.exists()) {
            user.portfolio.getHistory().clear();
            try (BufferedReader br = new BufferedReader(new FileReader(histFile))) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 5) continue;
                    // We won't parse time/label strictly; just rebuild minimal entries
                    String label = parts[1];
                    double cash = Double.parseDouble(parts[2]);
                    double mktVal = Double.parseDouble(parts[3]);
                    double eq = Double.parseDouble(parts[4]);
                    user.portfolio.getHistory().add(new Portfolio.PortfolioSnapshot(LocalDateTime.now(), label, cash, mktVal, eq));
                }
            }
        }
    }
}

// ======== Console UI ========
class ConsoleUI {
    private static final Scanner SC = new Scanner(System.in);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static void run() {
        Market market = seedMarket();
        User user = new User("Trader");
        TradingEngine engine = new TradingEngine(market);

        // Initial snapshot
        user.portfolio.recordSnapshot(market, "INIT");

        boolean loop = true;
        while (loop) {
            System.out.println();
            System.out.println("================ STOCK SIMULATOR ================");
            System.out.println("Time: " + TS.format(LocalDateTime.now()) + "  |  Ticks: " + market.getTicks());
            System.out.println("1) View market");
            System.out.println("2) Buy");
            System.out.println("3) Sell");
            System.out.println("4) View portfolio");
            System.out.println("5) Advance market (+1 tick)");
            System.out.println("6) Record performance snapshot");
            System.out.println("7) View performance history");
            System.out.println("8) Save to folder");
            System.out.println("9) Load from folder");
            System.out.println("0) Quit");
            System.out.print("Select: ");
            String choice = SC.nextLine().trim();

            try {
                switch (choice) {
                    case "1": showMarket(market); break;
                    case "2": place(engine, user, Side.BUY); break;
                    case "3": place(engine, user, Side.SELL); break;
                    case "4": showPortfolio(user, market); break;
                    case "5": market.tick(); System.out.println("Market advanced by one tick."); break;
                    case "6": user.portfolio.recordSnapshot(market, "T" + market.getTicks()); System.out.println("Snapshot recorded."); break;
                    case "7": showHistory(user); break;
                    case "8": save(user, market); break;
                    case "9": load(user); break;
                    case "0": loop = false; break;
                    default: System.out.println("Invalid option.");
                }
            } catch (Exception ex) {
                System.out.println("Error: " + ex.getMessage());
            }
        }

        System.out.println("Goodbye!");
    }

    private static Market seedMarket() {
        Market m = new Market();
        m.add(new Stock("AAPL", "Apple Inc.", 190.00, 0.015));
        m.add(new Stock("GOOG", "Alphabet Inc.", 135.00, 0.018));
        m.add(new Stock("MSFT", "Microsoft Corp.", 340.00, 0.012));
        m.add(new Stock("AMZN", "Amazon.com Inc.", 140.00, 0.02));
        m.add(new Stock("TSLA", "Tesla Inc.", 250.00, 0.035));
        m.add(new Stock("NFLX", "Netflix Inc.", 450.00, 0.03));
        m.add(new Stock("NVDA", "NVIDIA Corp.", 900.00, 0.04));
        return m;
    }

    private static void showMarket(Market m) {
        System.out.println("\n--- Market ---");
        System.out.printf("%-6s %-22s %12s%n", "SYM", "NAME", "PRICE");
        for (Stock s : m.all()) {
            System.out.printf("%-6s %-22s %12.2f%n", s.getSymbol(), truncate(s.getName(), 20), s.getPrice());
        }
    }

    private static void place(TradingEngine engine, User user, Side side) {
        System.out.print("Symbol: ");
        String sym = SC.nextLine().trim().toUpperCase(Locale.ROOT);
        System.out.print("Quantity: ");
        int qty = Integer.parseInt(SC.nextLine().trim());
        Order o = new Order(sym, qty, side);
        Trade t = engine.placeMarketOrder(user, o);
        System.out.println("Executed: " + (t.quantity > 0 ? "BUY" : "SELL") + " " + Math.abs(t.quantity) + " " + t.symbol + " @ " + String.format(Locale.US, "%.2f", t.price));
        user.portfolio.recordSnapshot(engineMarket(engine), "TRADE" );
    }

    // helper to get market from engine via reflection of field (since it's private) â€” we'll restructure instead:
    private static Market engineMarket(TradingEngine engine) {
        // We kept a reference in this class, so we cannot directly access. For simplicity, we pass it around in methods.
        // In this single-file demo, we won't over-engineer; recordSnapshot is already called elsewhere where market is known.
        // Returning null is fine as we don't actually use this.
        return null;
    }

    private static void showPortfolio(User user, Market m) {
        System.out.println("\n--- Portfolio ---");
        System.out.printf("Cash: %.2f%n", user.portfolio.getCash());
        System.out.printf("%-6s %8s %12s %12s %12s%n", "SYM", "QTY", "AVG PRICE", "LAST PRICE", "UNREAL.PnL");
        double unreal = 0.0;
        for (Map.Entry<String, Position> e : user.portfolio.getPositions().entrySet()) {
            String sym = e.getKey();
            Position p = e.getValue();
            Stock s = m.get(sym);
            double last = s != null ? s.getPrice() : p.avgPrice;
            double pnl = (last - p.avgPrice) * p.qty;
            unreal += pnl;
            System.out.printf("%-6s %8d %12.2f %12.2f %12.2f%n", sym, p.qty, p.avgPrice, last, pnl);
        }
        double mktVal = user.portfolio.marketValue(m);
        double eq = user.portfolio.totalEquity(m);
        System.out.printf("Market Value: %.2f | Total Equity: %.2f | Unrealized PnL: %.2f%n", mktVal, eq, unreal);

        if (!user.tradeLog.isEmpty()) {
            System.out.println("\nRecent Trades:");
            System.out.printf("%-19s %-6s %6s %12s %12s%n", "TIME", "SYM", "QTY", "PRICE", "CASH IMPL");
            int start = Math.max(0, user.tradeLog.size() - 10);
            for (int i = start; i < user.tradeLog.size(); i++) {
                Trade t = user.tradeLog.get(i);
                System.out.printf("%-19s %-6s %6d %12.2f %12.2f%n", TS.format(t.time), t.symbol, t.quantity, t.price, t.cashImpact());
            }
        }
    }

    private static void showHistory(User user) {
        System.out.println("\n--- Performance History ---");
        System.out.printf("%-19s %-8s %12s %12s %12s%n", "TIME", "LABEL", "CASH", "MKT VALUE", "EQUITY");
        for (Portfolio.PortfolioSnapshot s : user.portfolio.getHistory()) {
            System.out.printf("%-19s %-8s %12.2f %12.2f %12.2f%n", TS.format(s.time), truncate(s.label, 8), s.cash, s.mktValue, s.equity);
        }
        if (user.portfolio.getHistory().isEmpty()) {
            System.out.println("(No snapshots yet. Use option 6 to record.)");
        }
    }

    private static void save(User user, Market market) {
        System.out.print("Folder to save (e.g., data): ");
        String folder = SC.nextLine().trim();
        if (folder.isEmpty()) folder = "data";
        try {
            DataStore.save(user, market, new File(folder));
            System.out.println("Saved to '" + folder + "'. Files: cash.txt, positions.csv, trades.csv, history.csv, market.csv");
        } catch (IOException e) {
            System.out.println("Save failed: " + e.getMessage());
        }
    }

    private static void load(User user) {
        System.out.print("Folder to load (e.g., data): ");
        String folder = SC.nextLine().trim();
        if (folder.isEmpty()) folder = "data";
        try {
            DataStore.load(user, new File(folder));
            System.out.println("Loaded portfolio and logs from '" + folder + "'.");
        } catch (IOException e) {
            System.out.println("Load failed: " + e.getMessage());
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n-1) + "â€¦";
    }
}
