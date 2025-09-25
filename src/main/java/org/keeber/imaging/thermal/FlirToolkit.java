package org.keeber.imaging.thermal;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.DoubleSummaryStatistics;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import lombok.Getter;

public class FlirToolkit {
    private FlirImage flir;
    @Getter private int[] data;
    @Getter Stats stats;

    public static class Stats {
        private int[] sorted;

        private Stats(int[] stats) {
            this.sorted = IntStream.of(stats).sorted().toArray();
        }

        public int getMax() {
            return this.sorted[this.sorted.length - 1];
        }

        public int getMin() {
            return this.sorted[0];
        }

        /**
         * The offset of the percentile value between the min (getMin()) and max (getMax()) 
         * values.
         * 
         * @param percentile 0 - 1.00 (ie: 0.25 == 25%)
         * @return the offset 0 - 1.00 between the min value and max.
         */
        public double getPercentileOffset(double percentile) {
            return ((getPercentileValue(percentile) * 1f) - getMin()) / (getMax() - getMin());
        }

        /**
         * The raw value from the array at the given fractional percentile.
         * 
         * @param percentile 0 - 1.00 (ie: 0.25 == 25%)
         * @return the int value.
         */
        public int getPercentileValue(double percentile) {
            return this.sorted[(int) Math.floor(Math.max(0, Math.min(1, percentile)) * (sorted.length - 1))];
        }

    }

    protected FlirToolkit(FlirImage flir) {
        this.flir = flir;
        this.data = IntStream.range(0, this.flir.rawValues.length).map(i -> Short.toUnsignedInt(this.flir.rawValues[i])).toArray();
        this.stats =new Stats(this.data);
    }


    /**
     * Return array of temperature values in Celcius calculated from the raw values using the formula from 
     * {@link https://rdrr.io/cran/Thermimage/src/R/raw2temp.R}
     * 
     * @return temperature values in Celcius
     */
    public double[] getTemperatures( ) {
        return getTemperatures(false);
    }

    /**
     * Return array of temperature values in Celcius or Fahrenheit calculated from the raw values using the formula from 
     * {@link https://rdrr.io/cran/Thermimage/src/R/raw2temp.R}
     * 
     * @param fahrenheit otherwise Celcius
     * @return temperatures in Fahrenheit or Celcius
     */
    public double[] getTemperatures(boolean fahrenheit) {
        // From https://rdrr.io/cran/Thermimage/src/R/raw2temp.R
        double E =      flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.Emissivity);                               // Emissivity - default 1, should be ~0.95 to 0.97 depending on source
        double OD =     flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.ObjectDistance);                           // Object distance in metres
        double RTemp =  flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.ReflectedApparentTemperature) - 273.15;    // Apparent reflected temperature - one value from FLIR file (oC), default 20C
        double ATemp =  flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.AtmosphericTemperature) - 273.15;          // Atmospheric temperature for tranmission loss - one value from FLIR file (oC) - default = RTemp
        double IRT =    flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.IRWindowTransmission);                     // Infrared Window transmission - default 1.  likely ~0.95-0.96. Should be empirically determined.
        //
        double RH =     flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.RelativeHumidity) * 100;                   // Relative humidity - default 50% // this is a float in out values.
        //
        double PR1 =    flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.PlanckR1);                                 // Constant (FLIR)
        double PB =     flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.PlanckB);                                  // Constant (FLIR)
        double PF =     flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.PlanckF);                                  // Constant (FLIR)
        double PO =     flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.PlanckO);                                  // Constant (FLIR)
        double PR2 =    flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.PlanckR2);                                 // Constant (FLIR)
        //
        double emissWind = 1 - IRT;
        double reflWind = 0;
        double h2o = (RH/100)*Math.exp(1.5587+0.06939*(ATemp)-0.00027816*Math.pow(ATemp, 2)+0.00000068455*Math.pow(ATemp, 3));
        //
        double ATA1 =   flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.AtmosphericTransAlpha1);                   // Constant 
        double ATA2 =   flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.AtmosphericTransAlpha2);                   // Constant
        double ATB1 =   flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.AtmosphericTransBeta1);                    // Constant
        double ATB2 =   flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.AtmosphericTransBeta2);                    // Constant
        double ATX =    flir.getProperty(FlirFormat.FlirRecord.Camera.Properties.AtmosphericTransX);                        // Constant
        //
        double tau1=ATX*Math.exp(-Math.sqrt(OD/2)*(ATA1+ATB1*Math.sqrt(h2o)))+(1-ATX)*Math.exp(-Math.sqrt(OD/2)*(ATA2+ATB2*Math.sqrt(h2o)));
        double tau2=ATX*Math.exp(-Math.sqrt(OD/2)*(ATA1+ATB1*Math.sqrt(h2o)))+(1-ATX)*Math.exp(-Math.sqrt(OD/2)*(ATA2+ATB2*Math.sqrt(h2o)));
        //
        double rawRefl1=PR1/(PR2*(Math.exp(PB/(RTemp+273.15))-PF))-PO;                                                      // # Radiance reflecting off the object before the window
        double rawRefl1Attn=(1-E)/E*rawRefl1;                                                                               // # Attn = the attenuated radiance (in raw units) 
        //
        double rawAtm1=PR1/(PR2*(Math.exp(PB/(ATemp+273.15))-PF))-PO;                                                       // # Radiance from the atmosphere (before the window)
        double rawAtm1Attn=(1-tau1)/E/tau1*rawAtm1;                                                                         // # Attn = the attenuated radiance (in raw units) 
        //
        double IRWTemp = RTemp;                                                                                             // I don't know why the lib does this but it does
        //
        double rawWind=PR1/(PR2*(Math.exp(PB/(IRWTemp+273.15))-PF))-PO;
        double rawWindAttn=emissWind/E/tau1/IRT*rawWind;
        //
        double rawRefl2=PR1/(PR2*(Math.exp(PB/(RTemp+273.15))-PF))-PO ;
        double rawRefl2Attn=reflWind/E/tau1/IRT*rawRefl2;
        //
        double rawAtm2=PR1/(PR2*(Math.exp(PB/(ATemp+273.15))-PF))-PO;
        double rawAtm2Attn=(1-tau2)/E/tau1/IRT/tau2*rawAtm2;
        //
        return IntStream.of(data).mapToDouble(d -> d).map(r -> 
            (r/E/tau1/IRT/tau2-rawAtm1Attn-rawAtm2Attn-rawWindAttn-rawRefl1Attn-rawRefl2Attn)).map(r -> 
                //temp.C
                PB/Math.log(PR1/(PR2*(r+PO))+PF) - 273.15
        ).map(d -> fahrenheit?d * (9d/5d) + 32:d).toArray();
    }


    public BufferedImage createColorbar(int[] palette) {
        return new BufferedImage(ColorModel.getRGBdefault(),
                        Raster.createWritableRaster(new SinglePixelPackedSampleModel(
                                DataBuffer.TYPE_INT,palette.length, 1,
                                new int[] { 0xFF0000, 0xFF00, 0xFF, 0xFF000000 }),
                                new DataBufferInt(palette, palette.length), new Point()),
                        false, null);
    }

    public BufferedImage asImageTransformed(IntStreamTransformer transformer,int max,int min) {
        return new BufferedImage(ColorModel.getRGBdefault(),
                Raster.createWritableRaster(new SinglePixelPackedSampleModel(
                        DataBuffer.TYPE_INT,flir.imageWd, flir.imageHt,
                        new int[] { 0xFF0000, 0xFF00, 0xFF, 0xFF000000 }),
                        new DataBufferInt(transform(transformer,max,min), data.length), new Point()),
                false, null);
    }

    /**
     * Processes the image using the built in palette (converted from `ycbcr` t `RGB`).
     * 
     * @return an image representation of the raw flir content
     */
    public BufferedImage asImageDefault() {
        return asImagePalletted(getDefaultPalette(),stats.getMax(),stats.getMin(),0x0,0x0);
    }

    /**
     * Get the default palette as an array of 24bit integers.
     * 
     * @return
     */
    public int[] getDefaultPalette(){
        return Arrays.stream(flir.paletteData).mapToInt(c -> ycbcrtoRGB(c)).toArray();
    }

    public BufferedImage asImagePalletted(int[] palette, int max, int min, int overColor, int underColor) {
      return asImageTransformed(
            (x,y,w,h,l,r) -> { return l < 0?underColor:l > 1?overColor:palette[(int) Math.round((palette.length - 1) * l)]; },
            max,
            min
        );
    }

    public String asPrettySVG() throws IOException {
        int[] palette = getDefaultPalette();
        // Image
        ByteArrayOutputStream img = new ByteArrayOutputStream();
        ImageIO.write(asImagePalletted(palette, stats.getMax(), stats.getMin(), 0x0, 0x0), "png", img);
        // Colorbar
        ByteArrayOutputStream bar = new ByteArrayOutputStream();
        ImageIO.write(createColorbar(palette), "png", bar);
        // Histogram
        int[] hist= createHistogram(palette.length, stats.getMax(), stats.getMin());
        double hmax = IntStream.of(hist).max().orElse(10);
        String d = IntStream.range(0,hist.length).mapToObj(i -> "L "+((i / (hist.length * 1d)) * (flir.imageWd - 10f))+" -"+(hist[i] / hmax * 50d)).collect(Collectors.joining(" "));
        
        String percentiles = IntStream.range(0, 10).mapToObj(i -> "M "+
            (((flir.imageWd - 10) * flir.getToolkit().getStats().getPercentileOffset(i / 10f)) + 5) +" "+
            (flir.imageHt - 5)+" V "+
            (flir.imageHt - 55)
        ).collect(Collectors.joining());
        DoubleSummaryStatistics temps = DoubleStream.of(getTemperatures()).summaryStatistics();
        return MessageFormat.format("""
<?xml version="1.0" encoding="UTF-8"?>
<svg width="{0}" height="{1}" version="1.1" viewBox="0 0 {0} {1}" xml:space="preserve" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
    <style>
        <![CDATA[
        text '{' font: 10px "Courier New", sans-serif;fill: #ffffff; '}'
        ]]>
    </style>
    <image width="{0}" height="{1}" preserveAspectRatio="none" xlink:href="data:image/png;base64,{2}"/>
    <image x="{3}" y="{4}" width="{5}" height="{6}" preserveAspectRatio="none" xlink:href="data:image/png;base64,{7}"/>
    <rect x="{3}" y="{4}" width="{5}" height="{6}" style="fill: none;stroke: #fffffff;stroke-opacity: 0.2;stroke-width: 1px;"/>
    <rect x="5" y="{1}" width="{5}" height="50" style="fill: #000000;fill-opacity: 0.3;stroke: #ffffff;stroke-opacity: 0.2;stroke-width: 0.5px;" transform="translate(0,-55)"/>
    <g transform="translate(5, {9}) scale(1 1)" >
        <path d="M 0 0 {8} V 0 Z" style="fill: #ffffff;fill-opacity: 0.5;stroke: #ffffff;stroke-opacity: 0.2;stroke-width: 0.5px;stroke-linejoin:round;"/>
    </g>
    <path d="{10}" style="fill: none;stroke: #ffffff;stroke-opacity: 0.5;stroke-width: 0.5px;"/>
    <text x="7" y="{1}" transform="translate(0,-27.25)">{11}°C</text>
    <text x="{0}" y="{1}" transform="translate(-7,-27.25)" text-anchor="end">{12}°C</text>
</svg>""",
            flir.imageWd,                                               // {0} Image Wd
            flir.imageHt,                                               // {1} Image Ht
            Base64.getEncoder().encodeToString(img.toByteArray()),      // {2} Image Data
            5,                                                          // {3} Colorbar X
            5,                                                          // {4} Colorbar Y
            flir.imageWd - 10,                                          // {5} Colorbar Wd
            10,                                                         // {6} Colorbar Ht
            Base64.getEncoder().encodeToString(bar.toByteArray()),      // {7} Colorbar Data
            d,                                                          // {8} Histogram Path
            flir.imageHt - 5,                                           // {9} Histogram Translate Y
            percentiles,                                                // {10} Percentile marks
            temps.getMin(),                                             // {11} Min temp
            temps.getMax()                                              // {12} Max temp
        );
    }

    public int[] createHistogram(int buckets, int max, int min) {
        int[] hist = new int[buckets]; // Use the existing code as a for each
        Arrays.stream(transform((x,y,w,h,l,r) -> {  if ( l >= 0 || l <= l ) { hist[(int) Math.round((buckets - 1) * l)]++; } return 0; },max,min)).toArray();
        return hist;
    }

    /**
     * Transforms the raw data (presented as ints) with the provided transformer instance.
     * 
     * @param transformer {@link org.keeber.imaging.thermal.FlirToolkit.IntStreamTransformer}
     * @return
     */
    public int[] transform(IntStreamTransformer transformer, int max, int min) {
        int[] data = getData();
        return IntStream.range(0, flir.imageHt).flatMap(y -> IntStream.range(0, flir.imageWd).map(x -> transformer.transform(
            x, 
            y,
            flir.imageWd,
            flir.imageHt, 
            (data[( (y *  flir.imageWd) + x )] - min * 1f) / (max - min * 1f),
            data[( (y *  flir.imageWd) + x )]
            )
        )).toArray();
    }

    /**
     * Convert YCrCb - the format of the default palette to RGB. There are other versions of this out there, 
     * this one appears to work quite well. Or use your own.
     * 
     * @param YCrCb
     * @return
     */
    private int ycbcrtoRGB(int[] YCrCb) {
        int r, g, b;
        //
        r = (int) (YCrCb[0] + 1.40200 * (YCrCb[1] - 0x80));
        g = (int) (YCrCb[0] - 0.34414 * (YCrCb[2] - 0x80) - 0.71414 * (YCrCb[1] - 0x80));
        b = (int) (YCrCb[0] + 1.77200 * (YCrCb[2] - 0x80));
        //
        r = (Math.max(0, Math.min(255, r)) << 16)   & 0x00FF0000;
        g = (Math.max(0, Math.min(255, g)) << 8)    & 0x0000FF00;
        b =  Math.max(0, Math.min(255, b))          & 0x000000FF;
        //
        return 0xFF000000 | r | g | b;
    }


    /**
     * Used to transform an int stream - usually to colors to render an image.
     */
    @FunctionalInterface
    public static interface IntStreamTransformer {

        /**
         * Provide an integer for the returned int array based on:
         * 
         * @param x position the current pixel in the image.
         * @param y position the current pixel in the image.
         * @param w width of the image.
         * @param h height of the image.
         * @param level the level of this raw pixel value between the max and min provided observed in the data.
         * @param raw the raw value of this pixel.
         * @return a integer.
         */
        public int transform(int x, int y, int w, int h,double level, int raw);

    }

}