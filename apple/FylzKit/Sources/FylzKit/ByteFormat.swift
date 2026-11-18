import Foundation

public enum ByteFormat {
    /// Decimal (SI) units, matching what Files shows, with one fractional digit above bytes.
    /// Locale-independent on purpose: the decimal separator is always ".", so a test or a log line
    /// reads the same everywhere.
    public static func string(_ bytes: UInt64) -> String {
        let units = ["B", "KB", "MB", "GB", "TB", "PB"]
        if bytes < 1000 { return "\(bytes) B" }
        var value = Double(bytes)
        var unit = 0
        while value >= 1000 && unit < units.count - 1 {
            value /= 1000
            unit += 1
        }
        let tenths = (value * 10).rounded() / 10
        // Rounding can carry into the next unit (999.96 KB -> 1000.0 KB); renormalise once.
        if tenths >= 1000 && unit < units.count - 1 {
            return "\(String(format: "%.1f", tenths / 1000)) \(units[unit + 1])"
        }
        return "\(String(format: "%.1f", tenths)) \(units[unit])"
    }
}
