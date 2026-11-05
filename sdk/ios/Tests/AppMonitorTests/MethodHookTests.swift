import Foundation
import ObjectiveC
import Testing

@testable import AppMonitor

/// Each test hooks its own method: a hook stays for the life of the process.
private final class Hooked: NSObject {
    var calls: [String] = []

    @objc dynamic func none() { calls.append("none") }
    @objc dynamic func one(_ a: AnyObject?) { calls.append("one \(a as? String ?? "")") }
    @objc dynamic func two(_ a: AnyObject?, b: AnyObject?) {
        calls.append("two \(a as? String ?? "") \(b as? String ?? "")")
    }
    @objc dynamic func flag(_ a: AnyObject?, on: Bool) { calls.append("flag \(on)") }
    @objc dynamic func wrongArity(_ a: AnyObject?) { calls.append("wrongArity") }
    @objc dynamic func returning() -> NSString { "original" }

    @objc dynamic func recursive(_ a: AnyObject?) {
        calls.append("recursive")
        if a != nil { recursive(nil) }
    }
    @objc dynamic func recursiveSeen(_ a: AnyObject?) {
        calls.append("recursive")
        if a != nil { recursiveSeen(nil) }
    }
}

private func method(_ name: String) throws -> Method {
    try #require(Hooks.own(Hooked.self, name))
}

@Suite struct MethodHookTests {
    @Test func beforeRunsTheBodyThenTheOriginal() throws {
        MethodHook.before(try method("none")) { ($0 as? Hooked)?.calls.append("body") }
        let hooked = Hooked()
        hooked.none()
        #expect(hooked.calls == ["body", "none"])
    }

    @Test func beforeKeepsOneArgument() throws {
        MethodHook.before(try method("one:")) { object, a in
            (object as? Hooked)?.calls.append("body \(a as? String ?? "")")
        }
        let hooked = Hooked()
        hooked.one("a" as NSString)
        #expect(hooked.calls == ["body a", "one a"])
    }

    @Test func beforeKeepsTwoArguments() throws {
        MethodHook.before(try method("two:b:")) { object, a, b in
            (object as? Hooked)?.calls.append("body \(a as? String ?? "") \(b as? String ?? "")")
        }
        let hooked = Hooked()
        hooked.two("a" as NSString, b: "b" as NSString)
        #expect(hooked.calls == ["body a b", "two a b"])
    }

    @Test func notReentrantSeesTheOutermostCallOnly() throws {
        MethodHook.before(try method("recursive:"), reentrant: false) { object, _ in
            (object as? Hooked)?.calls.append("body")
        }
        let hooked = Hooked()
        hooked.recursive("a" as NSString)
        hooked.recursive(nil)
        #expect(hooked.calls == ["body", "recursive", "recursive", "body", "recursive"])
    }

    @Test func reentrantSeesEveryCall() throws {
        MethodHook.before(try method("recursiveSeen:")) { object, _ in (object as? Hooked)?.calls.append("body") }
        let hooked = Hooked()
        hooked.recursiveSeen("a" as NSString)
        #expect(hooked.calls == ["body", "recursive", "body", "recursive"])
    }

    @Test func replaceTakesOtherTypes() throws {
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject?, Bool) -> Void
        MethodHook.replace(try method("flag:on:"), arguments: 2, Original.self) { original, selector in
            let block: @convention(block) (AnyObject, AnyObject?, Bool) -> Void = { object, a, on in
                original(object, selector, a, !on)
            }
            return block
        }
        let hooked = Hooked()
        hooked.flag(nil, on: true)
        #expect(hooked.calls == ["flag false"])
    }

    @Test func replaceReturnsTheBlocksValue() throws {
        typealias Original = @convention(c) (AnyObject, Selector) -> NSString
        MethodHook.replace(try method("returning"), arguments: 0, Original.self) { original, selector in
            let block: @convention(block) (AnyObject) -> NSString = { object in
                "hooked \(original(object, selector))" as NSString
            }
            return block
        }
        #expect(Hooked().returning() == "hooked original")
    }

    @Test func aWrongArityLeavesTheMethodAlone() throws {
        let target = try method("wrongArity:")
        let before = method_getImplementation(target)
        MethodHook.before(target) { (_: AnyObject) in Issue.record("hooked") }
        #expect(method_getImplementation(target) == before)
        let hooked = Hooked()
        hooked.wrongArity(nil)
        #expect(hooked.calls == ["wrongArity"])
    }
}
