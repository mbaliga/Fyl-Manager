import Foundation

/// Plain-language text for each failure. The policy's own reason is shown verbatim where there is
/// one: the app's design thesis is that a refusal says what rule fired, not just "failed".
public enum FailurePresenter {
    public static func title(_ failure: EngineFailure) -> String {
        switch failure {
        case .unsupported: return "Not an archive Fylz can open"
        case .corrupt: return "This archive looks damaged"
        case .refused: return "Fylz will not extract this archive"
        case .limitExceeded: return "Stopped at a safety limit"
        case .unsafePath: return "An entry tried to leave the folder"
        case .destinationExists: return "Something is already there"
        case .io: return "Could not read or write a file"
        case .failed: return "One entry failed its check"
        case .internalError: return "Fylz hit a bug"
        }
    }

    public static func detail(_ failure: EngineFailure) -> String {
        switch failure {
        case .unsupported(let d), .corrupt(let d), .io(let d), .failed(let d), .internalError(let d):
            return d
        case .refused(let reason):
            return reason + " Nothing was written."
        case .limitExceeded(let entry, let rule):
            return "The \"\(rule)\" limit was reached at \"\(entry)\". Nothing was left behind."
        case .unsafePath(let path):
            return "\"\(path)\" would have been written outside the destination or through a link. Nothing was written."
        case .destinationExists(let path):
            return "\"\(path)\" already exists, and Fylz never overwrites. Nothing was changed."
        }
    }
}
