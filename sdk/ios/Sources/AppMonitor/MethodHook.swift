import Foundation
import ObjectiveC
import os

/// Replaces an Objective-C method's implementation for the capture hooks (ADR-0187). `before` covers methods
/// taking only objects; `replace` is the rest. The original always runs with the same arguments.
enum MethodHook {
    /// Replaces `method`, whose C signature is `Original`, receiver and selector first, with the block `make`
    /// returns. A method not taking `arguments` arguments, those two left out, is left alone: `Original` is wrong.
    static func replace<Original>(
        _ method: Method,
        arguments: Int,
        _: Original.Type,
        _ make: (Original, Selector) -> Any
    ) {
        let selector = method_getName(method)
        guard Int(method_getNumberOfArguments(method)) == arguments + 2 else {
            Logger(subsystem: "AppMonitor", category: "capture").error(
                "Not hooked, wrong arity: \(NSStringFromSelector(selector), privacy: .public)")
            return
        }
        let original = unsafeBitCast(method_getImplementation(method), to: Original.self)
        method_setImplementation(method, imp_implementationWithBlock(make(original, selector)))
    }

    static func before(_ method: Method, _ body: @escaping (AnyObject) -> Void) {
        typealias Original = @convention(c) (AnyObject, Selector) -> Void
        replace(method, arguments: 0, Original.self) { original, selector in
            let block: @convention(block) (AnyObject) -> Void = { object in
                body(object)
                original(object, selector)
            }
            return block
        }
    }

    /// `reentrant` false: a call the original makes to itself on the same thread is not seen again; CFNetwork
    /// hands `_didReceiveData:` the same data twice that way.
    static func before(
        _ method: Method,
        reentrant: Bool = true,
        _ body: @escaping (AnyObject, AnyObject?) -> Void
    ) {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?) -> Void
        let key = "appmonitor.\(NSStringFromSelector(method_getName(method)))"
        replace(method, arguments: 1, Original.self) { original, selector in
            let block: @convention(block) (AnyObject, AnyObject?) -> Void = { object, argument in
                let thread = Thread.current.threadDictionary
                if reentrant || thread[key] == nil { body(object, argument) }
                if reentrant { return original(object, selector, argument) }
                let outer = thread[key] == nil
                if outer { thread[key] = true }
                original(object, selector, argument)
                if outer { thread[key] = nil }
            }
            return block
        }
    }

    static func before(_ method: Method, _ body: @escaping (AnyObject, AnyObject?, AnyObject?) -> Void) {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?, AnyObject?) -> Void
        replace(method, arguments: 2, Original.self) { original, selector in
            let block: @convention(block) (AnyObject, AnyObject?, AnyObject?) -> Void = { object, first, second in
                body(object, first, second)
                original(object, selector, first, second)
            }
            return block
        }
    }
}
