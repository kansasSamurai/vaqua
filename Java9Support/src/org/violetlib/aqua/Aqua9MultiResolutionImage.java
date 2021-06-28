/*
 * Copyright (c) 2016-2020 Alan Snyder.
 * All rights reserved.
 *
 * You may not use, copy or modify this file, except in compliance with the license agreement. For details see
 * accompanying license terms.
 */

package org.violetlib.aqua;

import java.awt.*;
import java.awt.image.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;

/**
 * A multi-resolution image with a single representation. This class is designed for Java 9.
 */

public class Aqua9MultiResolutionImage extends AquaMultiResolutionImage implements MultiResolutionImage {

    public Aqua9MultiResolutionImage(BufferedImage im) {
        super(im);
    }

    public Aqua9MultiResolutionImage(int width, int height, BufferedImage im) {
        super(im, width, height);
    }

    @Override
    public Image getResolutionVariant(double width, double height) {
        return baseImage;
    }

    @Override
    public List<Image> getResolutionVariants() {
        List<Image> result = new ArrayList<>();
        result.add(baseImage);
        return result;
    }

    @Override
    public AquaMultiResolutionImage map(Mapper mapper) {
        int scaleFactor = baseImageWidth / baseImage.getWidth(null);
        return new Aqua9MultiResolutionImage(mapper.map(baseImage, scaleFactor));
    }

    @Override
    public AquaMultiResolutionImage map(Function<Image, Image> mapper) {
        return new Aqua9MultiResolutionImage(Images.toBufferedImage(mapper.apply(baseImage)));
    }

    /**
     * Create an image by applying a filter. Supports multi-resolution images.
     */
    public static Image apply(Image image, ImageFilter filter) {
        if (image instanceof MultiResolutionImage) {
            Function<Image,Image> f = (rv -> createFilteredImage(rv, filter));
            return apply(image, f);
        }

        return createFilteredImage(image, filter);
    }

    private static @NotNull Image createFilteredImage(@NotNull Image image, @NotNull ImageFilter filter) {
        int width = image.getWidth(null);
        int height = image.getHeight(null);
        ImageProducer prod = new FilteredImageSource(image.getSource(), filter);
        return waitForImage(Toolkit.getDefaultToolkit().createImage(prod), width, height);
    }

    // The following is a workaround for a JDK 8 problem - trying to draw a MultiResolutionImage that is not ready
    // throws an exception.

    private static @NotNull Image waitForImage(@NotNull Image image, int width, int height) {
        final boolean[] mutex = new boolean[] { false };
        ImageObserver observer = (Image img, int infoflags, int x, int y, int w, int h) -> {
            int required = ImageObserver.ALLBITS;
            if ((infoflags & required) == required || (infoflags & (ImageObserver.ERROR | ImageObserver.ABORT)) != 0) {
                synchronized (mutex) {
                    mutex[0] = true;
                    mutex.notify();
                }
                return false;
            } else {
                return true;
            }
        };
        if (!Toolkit.getDefaultToolkit().prepareImage(image, width, height, observer)) {
            synchronized (mutex) {
                while (!mutex[0]) {
                    try {
                        mutex.wait();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
        return image;
    }

    /**
     * Create an image by applying a generic mapper. Supports multi-resolution images.
     */
    public static Image apply(Image source, Function<Image,Image> mapper) {
        if (source instanceof Aqua9MappedMultiResolutionImage) {
            Aqua9MappedMultiResolutionImage s = (Aqua9MappedMultiResolutionImage) source;
            return s.map(mapper::apply);
        }

        if (source instanceof AquaMultiResolutionImage) {
            AquaMultiResolutionImage s = (AquaMultiResolutionImage) source;
            return s.map(mapper);
        }

        if (source instanceof MultiResolutionImage) {
            MultiResolutionImage s = (MultiResolutionImage) source;
            return new Aqua9MappedMultiResolutionImage(s, mapper);
        }

        return mapper.apply(source);
    }

    /**
     * Create an image by applying a specialized mapper. Supports multi-resolution images.
     */
    public static Image apply(Image source, AquaMultiResolutionImage.Mapper mapper) {
        if (source instanceof Aqua9MappedMultiResolutionImage) {
            Aqua9MappedMultiResolutionImage s = (Aqua9MappedMultiResolutionImage) source;
            int width = s.getWidth(null);
            return s.map(rv -> mapper.map(rv, rv.getWidth(null) / width));
        }

        if (source instanceof AquaMultiResolutionImage) {
            AquaMultiResolutionImage s = (AquaMultiResolutionImage) source;
            return s.map(mapper);
        }

        // The following allows optimized implementations.
        if (source instanceof MultiResolutionImage) {
            MultiResolutionImage im = (MultiResolutionImage) source;
            try {
                Method m = im.getClass().getMethod("map", Function.class);
                m.setAccessible(true);
                Object o = m.invoke(im, mapper);
                if (o instanceof Image) {
                    return (Image) o;
                }
            } catch (NoSuchMethodException ex) {
                // ignore
            } catch (Exception ex) {
                if (ex instanceof InvocationTargetException) {
                    Throwable th = ((InvocationTargetException) ex).getTargetException();
                    Utils.logError("Unable to map image", th);
                } else {
                    Utils.logError("Unable to map image", ex);
                }
            }
        }

        return mapper.map(source, 1);
    }
}
