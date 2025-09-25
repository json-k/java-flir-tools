package org.keeber.imaging.thermal;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
/**
 * 
 * All comments and descriptions in this file are taken from: 
 * @see <a href="https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/FLIR.pm">ExifTool/FLIR.pm</a>
 */
public class FlirFormat {

    public static class Palettes {

        public static int[] WITEHOT = IntStream.range(0, 255).map(n -> (n * 0x010101) | 0xFF << 24).toArray();
        public static int[] DARKHOT = IntStream.range(0, 255).map(n -> ((255-n) * 0x010101) | 0xFF << 24).toArray();
        public static int[] FAKEBOW = gradient(new int[]{
            0xff00000a,
            0xff3b0091,
            0xff98009b,
            0xffcc1582,
            0xffe94d0d,
            0xfff78500,
            0xfffec100,
            0xffffef63,
            0xfffffff6,
        }, 64);
        public static int[] WIDEBOW = gradient(new int[]{
            0xff000000,
            0xff000080,
            0xff0000ff,
            0xff8000ff,
            0xffff0080,
            0xffff0000,
            0xffff8000,
            0xffffff00,
            0xffffff80,
            0xffffffff,
        }, 127);

        private static int[] gradient(int[] colors, float steps) {
            return IntStream.range(0,colors.length)
            .flatMap(i -> {
                if (i == (colors.length-1)) { 
                    return IntStream.of(colors[i]);
                }
                int[] c1 = new int[]{colors[i] >> 24 & 0xff,colors[i] >> 16 & 0xff,colors[i] >> 8 & 0xff,colors[i] & 0xff};
                int[] c2 = new int[]{colors[i+1] >> 24 & 0xff,colors[i+1] >> 16 & 0xff,colors[i+1] >> 8 & 0xff,colors[i+1] & 0xff};
                return IntStream.range(0,(int) steps).map(j -> 
                (c1[0] + Math.round((c2[0] - c1[0]) * (j / steps ))) << 24 |
                (c1[1] + Math.round((c2[1] - c1[1]) * (j / steps ))) << 16 |
                (c1[2] + Math.round((c2[2] - c1[2]) * (j / steps ))) << 8 |
                (c1[3] + Math.round((c2[3] - c1[3]) * (j / steps )))
            );
            })
            .toArray();
        }
    }

    public static class ThermalJpeg {

        public static class Index {
            public static int APP1HEADER        = 0x8;         // Header length of the APP1 section
        }
    }

    public static class Header {
        public static final String FORMAT = "FFF\0";
    
        public static class Index {
            public static int FORMAT            = 0x00;         // File format = FlirHeader.FORMAT
            public static int CREATOR           = 0x04;         // File creator: seen "\0","MTX IR\0","CAMCTRL\0"
            public static int VERSION           = 0x14;         // File format version = 100 
            public static int RECORD_OFFSET     = 0x18;         // Offset to record directory 
            public static int RECORD_NUMBER     = 0x1C;         // Number of entries in record directory
            public static int INDEX_ID          = 0x20;         // Next free index ID = 2
            public static int SWAP_PATTERN      = 0x24;         // Swap pattern = 0 (?)
            public static int SPARES            = 0x28;         // Spares
            public static int RESERVED          = 0x34;         // Reserved
            public static int CHECKSUM          = 0x3C;         // Checksum
        }
    
    }

    public static class FlirRecord {

        /**
         * Why is this not an enum? You can't have a generic enum in Java.
         */
        @RequiredArgsConstructor
        public static class FlirProperty<T> implements Cloneable {

            public enum Type {
                INT32, INT16S,
                INT16U, 
                STR32, STR16, 
                FLOAT, BYTE1, 
                COLOR;
            }

            @NonNull @Getter transient Integer index;
            @NonNull @Getter String key;
            @NonNull @Getter transient Type type;
            @Getter @Setter(AccessLevel.PROTECTED) @Accessors(chain = true) String category;
            private T value;
            public T getValue() { return this.value; }
            @SuppressWarnings("unchecked") void setValue(Object value) { this.value = (T) value; }

            public FlirProperty<T> clone() {
                return new FlirProperty<T>(index, key, type);
            }
        }

        /**
         * Flir 'Camera' record type `0x20`.
         * 
         * This record contains properties of the 'camera' which are ultimately required 
         * (when combined with the raw data values) to calculate the actual temperature values.
         */
        public static class Camera {

            public static List<FlirProperty<?>> allProperties() {
                return listPropertiesOf(Camera.Properties.class,Camera.class.getSimpleName());
            }

            public static class Properties { // This class should only contain static FlirProperties (otherwise the `allProperties` method needs modifing to be more specific).
                public static FlirProperty<Float>   Emissivity = new FlirProperty<Float>(                   0x020, "Emissivity",                    FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   ObjectDistance = new FlirProperty<Float>(               0x024, "ObjectDistance",                FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   ReflectedApparentTemperature = new FlirProperty<Float>( 0x028, "ReflectedApparentTemperature",  FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   AtmosphericTemperature = new FlirProperty<Float>(       0x02C, "AtmosphericTemperature",        FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   IRWindowTemperature = new FlirProperty<Float>(          0x030, "IRWindowTemperature",           FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   IRWindowTransmission = new FlirProperty<Float>(         0x034, "IRWindowTransmission",          FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   RelativeHumidity = new FlirProperty<Float>(             0x03C, "RelativeHumidity",              FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   PlanckR1 = new FlirProperty<Float>(                     0x058, "PlanckR1",                      FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   PlanckB = new FlirProperty<Float>(                      0x05C, "PlanckB",                       FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   PlanckF = new FlirProperty<Float>(                      0x060, "PlanckF",                       FlirProperty.Type.FLOAT);
                public static FlirProperty<Integer> PlanckO = new FlirProperty<Integer>(                    0x308, "PlanckO",                       FlirProperty.Type.INT32);
                public static FlirProperty<Float>   PlanckR2 = new FlirProperty<Float>(                     0x30C, "PlanckR2",                      FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   AtmosphericTransAlpha1 = new FlirProperty<Float>(       0x070, "AtmosphericTransAlpha1",        FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   AtmosphericTransAlpha2 = new FlirProperty<Float>(       0x074, "AtmosphericTransAlpha2",        FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   AtmosphericTransBeta1 = new FlirProperty<Float>(        0x078, "AtmosphericTransBeta1",         FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   AtmosphericTransBeta2 = new FlirProperty<Float>(        0x07C, "AtmosphericTransBeta2",         FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   AtmosphericTransX = new FlirProperty<Float>(            0x080, "AtmosphericTransX",             FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   CameraTemperatureRangeMax = new FlirProperty<Float>(    0x090, "CameraTemperatureRangeMax",     FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   CameraTemperatureRangeMin = new FlirProperty<Float>(    0x094, "CameraTemperatureRangeMin",     FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   CameraTemperatureMaxClip = new FlirProperty<Float>(     0x098, "CameraTemperatureMaxClip",      FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   CameraTemperatureMinClip = new FlirProperty<Float>(     0x09C, "CameraTemperatureMinClip",      FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   CameraTemperatureMaxWarn = new FlirProperty<Float>(     0x0A0, "CameraTemperatureMaxWarn",      FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   CameraTemperatureMinWarn = new FlirProperty<Float>(     0x0A4, "CameraTemperatureMinWarn",      FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   CameraTemperatureMaxSaturated = new FlirProperty<Float>(0x0A8, "CameraTemperatureMaxSaturated", FlirProperty.Type.FLOAT);
                public static FlirProperty<Float>   CameraTemperatureMinSaturated = new FlirProperty<Float>(0x0AC, "CameraTemperatureMinSaturated", FlirProperty.Type.FLOAT);
                public static FlirProperty<String>  CameraModel = new FlirProperty<String>(                 0x0D4, "CameraModel",                   FlirProperty.Type.STR32);
                public static FlirProperty<String>  CameraPartNumber = new FlirProperty<String>(            0x0F4, "CameraPartNumber",              FlirProperty.Type.STR16);
                public static FlirProperty<String>  CameraSerialNumber = new FlirProperty<String>(          0x104, "CameraSerialNumber",            FlirProperty.Type.STR16);
                public static FlirProperty<String>  CameraSoftware = new FlirProperty<String>(              0x114, "CameraSoftware",                FlirProperty.Type.STR16);
                //
                public static FlirProperty<String>  LensModel = new FlirProperty<String>(                   0x170, "LensModel",                     FlirProperty.Type.STR32);
                public static FlirProperty<String>  LensPartNumber = new FlirProperty<String>(              0x190, "LensPartNumber",                FlirProperty.Type.STR16);
                public static FlirProperty<String>  LensSerialNumber = new FlirProperty<String>(            0x1A0, "LensSerialNumber",              FlirProperty.Type.STR16);
                //
                public static FlirProperty<String>  FilterModel = new FlirProperty<String>(                 0x1EC, "FilterModel",                   FlirProperty.Type.STR16);
                public static FlirProperty<String>  FilterPartNumber = new FlirProperty<String>(            0x1FC, "FilterPartNumber",              FlirProperty.Type.STR32);
                public static FlirProperty<String>  FilterSerialNumber = new FlirProperty<String>(          0x21C, "FilterSerialNumber",            FlirProperty.Type.STR32);
                //
                public static FlirProperty<Integer> RawValueRangeMin = new FlirProperty<Integer>(           0x310, "RawValueRangeMin",              FlirProperty.Type.INT16U); 
                public static FlirProperty<Integer> RawValueRangeMax = new FlirProperty<Integer>(           0x312, "RawValueRangeMax",              FlirProperty.Type.INT16U); 
                public static FlirProperty<Integer> RawValueMedian = new FlirProperty<Integer>(             0x338, "RawValueMedian",                FlirProperty.Type.INT32);
                public static FlirProperty<Integer> RawValueRange = new FlirProperty<Integer>(              0x33C, "RawValueRange",                 FlirProperty.Type.INT32);
                //
                public static FlirProperty<Integer> DateTimeOriginal = new FlirProperty<Integer>(           0x384, "DateTimeOriginal",              FlirProperty.Type.INT32);
                //
                public static FlirProperty<Integer> FocusStepCount = new FlirProperty<Integer>(             0x390, "FocusStepCount",                FlirProperty.Type.INT32);
                public static FlirProperty<Float>   FocusDistance = new FlirProperty<Float>(                0x45C, "FocusDistance",                 FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Float>   FieldOfView = new FlirProperty<Float>(                  0x1B4, "FieldOfView",                   FlirProperty.Type.FLOAT);
                //
                public static FlirProperty<Integer> Framerate = new FlirProperty<Integer>(                  0x464, "FrameRate",                     FlirProperty.Type.INT16U);
            }
        }

        /**
         * Flir 'Palette' record type `0x22`.
         * 
         * This record contains an embedded palette that can be used to render the file.
         */
        public static class Palette {

            public static class Index {
                public static int COLORS        = 0x00;
                public static int DATA          = 0x70;
            }

            public static List<FlirProperty<?>> allProperties() {
                return listPropertiesOf(Palette.Properties.class,Palette.class.getSimpleName());
            }

            public static class Properties { // This class should only contain static FlirProperties (otherwise the `allProperties` method needs modifing to be more specific).
                public static FlirProperty<Integer> PaletteColors = new FlirProperty<Integer>(              0x000, "PaletteColors",                 FlirProperty.Type.INT32);
                public static FlirProperty<String> PaletteFileName = new FlirProperty<String>(              0x030, "PaletteFileName",               FlirProperty.Type.STR32);
                public static FlirProperty<String> PaletteName = new FlirProperty<String>(                  0x050, "PaletteName",                   FlirProperty.Type.STR32);
                public static FlirProperty<Integer> PaletteMethod = new FlirProperty<Integer>(              0x01A, "PaletteMethod",                 FlirProperty.Type.BYTE1);
                public static FlirProperty<Integer> PaletteStretch = new FlirProperty<Integer>(             0x01B, "PaletteStretch",                FlirProperty.Type.BYTE1);
                public static FlirProperty<Integer[]> AboveColor = new FlirProperty<Integer[]>(             0x006, "AboveColor",                    FlirProperty.Type.COLOR);
                public static FlirProperty<Integer[]> BelowColor = new FlirProperty<Integer[]>(             0x009, "BelowColor",                    FlirProperty.Type.COLOR);
                public static FlirProperty<Integer[]> OverflowColor = new FlirProperty<Integer[]>(          0x00C, "OverflowColor",                 FlirProperty.Type.COLOR);
                public static FlirProperty<Integer[]> UnderflowColor = new FlirProperty<Integer[]>(         0x00F, "UnderflowColor",                FlirProperty.Type.COLOR);
                public static FlirProperty<Integer[]> Isotherm1Color = new FlirProperty<Integer[]>(         0x012, "Isotherm1Color",                FlirProperty.Type.COLOR);
                public static FlirProperty<Integer[]> Isotherm2Color = new FlirProperty<Integer[]>(         0x015, "Isotherm2Color",                FlirProperty.Type.COLOR);
            }
        }

        /**
         * Flir 'Raw' record type `0x01`.
         * 
         * This record contains the actual image data. This is the record that ExifTool
         * 'converts' to a TIFF - by essentially assigning the values in this record to the images 
         * databuffer.
         * 
         * The data is either Big Endian (sub-type == BE), Little Endian (sub-type == LE),
         * or a PNG - the later of which is currently unsupported by this library.
         */
        public static class Raw {

            public static class Index {
                public static int WIDTH         = 0x02;         // Width  of the raw image
                public static int HEIGHT        = 0x04;         // Height of the raw image
                public static int DATA          = 0x20;         // Raw data (length == the record lenght - this index (0x20))
            }
        }

        public static class Pip {

            public static List<FlirProperty<?>> allProperties() {
                return listPropertiesOf(Pip.Properties.class,Pip.class.getSimpleName());
            }

            public static class Properties { // This class should only contain static FlirProperties (otherwise the `allProperties` method needs modifing to be more specific).
                public static FlirProperty<Float> Real2IR = new FlirProperty<Float>(                        0x000, "Real2IR",                       FlirProperty.Type.FLOAT);
                public static FlirProperty<Integer> OffsetX = new FlirProperty<Integer>(                    0x004, "OffsetX",                       FlirProperty.Type.INT16S);
                public static FlirProperty<Integer> OffsetY = new FlirProperty<Integer>(                    0x006, "OffsetY",                       FlirProperty.Type.INT16S);
                public static FlirProperty<Integer> PiPX1 = new FlirProperty<Integer>(                      0x008, "X1",                            FlirProperty.Type.INT16S);
                public static FlirProperty<Integer> PiPX2 = new FlirProperty<Integer>(                      0x00A, "X2",                            FlirProperty.Type.INT16S);
                public static FlirProperty<Integer> PiPY1 = new FlirProperty<Integer>(                      0x00C, "Y1",                            FlirProperty.Type.INT16S);
                public static FlirProperty<Integer> PiPY2 = new FlirProperty<Integer>(                      0x00E, "Y2",                            FlirProperty.Type.INT16S);
            }

        }

        public static List<FlirProperty<?>> listPropertiesOf(Class<?> clazz, String category ) {
            return Arrays.stream(clazz.getDeclaredFields()).<FlirProperty<?>>map(f -> {
                try {
                    return ((FlirProperty<?>) f.get(null)).clone().setCategory(category);
                } catch (IllegalArgumentException | IllegalAccessException e) {
                    throw new RuntimeException(e); // This is reading static code, it is runs once it should always run.
                }
            }).toList();
        }

        public static int LENGTH                = 0x20;         // Record length

        public static class Index {
            public static int TYPE              = 0x00;         // Record type (FlirRecord.Type)
            public static int SUB_TYPE          = 0x02;         // Record subtype: (FlirRecord.Type.SubType)
            public static int VERSION           = 0x04;         // Record version
            public static int INDEX             = 0x08;         // Index
            public static int OFFSET            = 0x0C;         // Record offset from start of FLIR data
            public static int LENGTH            = 0x10;         // Record length
            public static int PARENT            = 0x14;         // Parent = 0 (?)
            public static int OBJECT_NUMBER     = 0x18;         // Object number = 0 (?)
            public static int CHECKSUM          = 0x1C;         // Checksum: 0 for no checksum
        }

        public static class Type {
            public static final int EMPTY       = 0x00;
            public static final int RAW         = 0x01;
            public static final int CAMERA      = 0x20;
            public static final int PALETTE     = 0x22;
            public static final int PIP         = 0x2A;

            public static class SubType {
                public static int EMPTY         = 0x00;
                public static int BE            = 0x01; 
                public static int LE            = 0x02; 
                public static int PNG           = 0x03;
            }
        }
    }


}
