import AppKit

@MainActor enum MenuIcon {
    static let image: NSImage = {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: true) { bounds in
            guard let context = NSGraphicsContext.current?.cgContext else { return false }
            context.scaleBy(x: bounds.width / 24, y: bounds.height / 24)
            context.setFillColor(NSColor.black.cgColor)
            let document = CGMutablePath()
            document.move(to: CGPoint(x: 9, y: 1)); document.addLine(to: CGPoint(x: 14, y: 1)); document.addLine(to: CGPoint(x: 18, y: 5)); document.addLine(to: CGPoint(x: 18, y: 12)); document.addLine(to: CGPoint(x: 14, y: 12)); document.addLine(to: CGPoint(x: 14, y: 14)); document.addLine(to: CGPoint(x: 16, y: 14)); document.addLine(to: CGPoint(x: 12, y: 18)); document.addLine(to: CGPoint(x: 8, y: 14)); document.addLine(to: CGPoint(x: 10, y: 14)); document.addLine(to: CGPoint(x: 10, y: 12)); document.addLine(to: CGPoint(x: 6, y: 12)); document.addLine(to: CGPoint(x: 6, y: 4)); document.addQuadCurve(to: CGPoint(x: 9, y: 1), control: CGPoint(x: 6, y: 1)); document.closeSubpath()
            context.addPath(document); context.fillPath()
            let monitor = CGMutablePath()
            monitor.move(to: CGPoint(x: 4, y: 9)); monitor.addLine(to: CGPoint(x: 2, y: 9)); monitor.addLine(to: CGPoint(x: 2, y: 20)); monitor.addLine(to: CGPoint(x: 10, y: 20)); monitor.addLine(to: CGPoint(x: 10, y: 22)); monitor.addLine(to: CGPoint(x: 6, y: 22)); monitor.addLine(to: CGPoint(x: 6, y: 24)); monitor.addLine(to: CGPoint(x: 18, y: 24)); monitor.addLine(to: CGPoint(x: 18, y: 22)); monitor.addLine(to: CGPoint(x: 14, y: 22)); monitor.addLine(to: CGPoint(x: 14, y: 20)); monitor.addLine(to: CGPoint(x: 22, y: 20)); monitor.addLine(to: CGPoint(x: 22, y: 9)); monitor.addLine(to: CGPoint(x: 20, y: 9)); monitor.addLine(to: CGPoint(x: 20, y: 18)); monitor.addLine(to: CGPoint(x: 4, y: 18)); monitor.closeSubpath()
            context.addPath(monitor); context.fillPath()
            return true
        }
        image.isTemplate = true
        return image
    }()
}
