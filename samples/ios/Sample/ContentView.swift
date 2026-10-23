import SwiftUI

struct ContentView: View {
    @State private var result = "Tap a button; the call shows up in the desktop."

    var body: some View {
        VStack(spacing: 8) {
            ForEach(SampleCalls.buttons, id: \.label) { button in
                Button {
                    run(button)
                } label: {
                    Text(button.label).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
            Text(result).frame(maxWidth: .infinity, alignment: .leading)
            Spacer()
        }
        .padding()
        .task {
            // `-runAll YES` at launch taps every button in turn.
            guard UserDefaults.standard.bool(forKey: "runAll") else { return }
            for button in SampleCalls.buttons { result = "\(button.label): \(await button.call())" }
        }
    }

    private func run(_ button: SampleButton) {
        result = "\(button.label)…"
        Task { result = "\(button.label): \(await button.call())" }
    }
}
