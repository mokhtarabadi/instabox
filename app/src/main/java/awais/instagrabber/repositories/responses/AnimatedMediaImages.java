package awais.instagrabber.repositories.responses;

public class AnimatedMediaImages {
    private final AnimatedMediaFixedHeight fixedHeight;

    public AnimatedMediaImages(final AnimatedMediaFixedHeight fixedHeight) {
        this.fixedHeight = fixedHeight;
    }

    public AnimatedMediaFixedHeight getFixedHeight() {
        return fixedHeight;
    }
}
