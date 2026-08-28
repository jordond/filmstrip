import SwiftUI
import Shared

// Compose owns the whole window. Safe areas are handled inside the composition, which is what lets
// the editor put its viewport under the status bar without the controls following it there.
struct ComposeView: UIViewControllerRepresentable {
  func makeUIViewController(context: Context) -> UIViewController {
    MainViewControllerKt.MainViewController()
  }

  func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
  var body: some View {
    ComposeView()
      .ignoresSafeArea(.all)
      .preferredColorScheme(.dark)
  }
}
