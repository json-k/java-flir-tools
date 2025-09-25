package org.keeber.imaging.thermal;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.keeber.imaging.thermal.FlirFormat.FlirRecord.FlirProperty;
import org.w3c.dom.NodeList;

import lombok.Getter;

public class FlirImage {
    @Getter String creator;
    @Getter int imageWd, imageHt;
    @Getter short[] rawValues;
    @Getter int[][] paletteData;
    @Getter List<FlirFormat.FlirRecord.FlirProperty<?>> properties = new ArrayList<>();
    private transient FlirToolkit toolkit;

    public FlirToolkit getToolkit() {
        return this.toolkit == null? toolkit = new FlirToolkit(this):toolkit;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(FlirProperty<T> p) throws NoSuchElementException {
        return (T) properties.stream().filter(fp -> fp.key.equals(p.key) ).findFirst().get().getValue();
    }

    public static FlirImage fromJPG(InputStream is) throws IOException, FlirImageException { 
        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
        if (reader == null) throw new IOException("No JPEG file reader found (system configuration error).");
        //
        ByteArrayOutputStream fff = new ByteArrayOutputStream();byte[] tmp;
        try (ImageInputStream iis = ImageIO.createImageInputStream(is)) {
            reader.setInput(iis, true, false);
            NodeList nodes = ((IIOMetadataNode) ((IIOMetadataNode) reader.getImageMetadata(0).getAsTree("javax_imageio_jpeg_image_1.0")).getElementsByTagName("markerSequence").item(0)).getElementsByTagName("unknown");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (((IIOMetadataNode) nodes.item(i)).getAttribute("MarkerTag").matches("APP1|225")) {
                    // TODO: look for an index to ensure these are in order.
                    tmp = (byte[]) ((IIOMetadataNode) nodes.item(i)).getUserObject();
                    if ( tmp != null && tmp.length >= 4 && "FLIR".equals(new String(tmp, 0, 4))) {
                        fff.write(tmp, FlirFormat.ThermalJpeg.Index.APP1HEADER, tmp.length - FlirFormat.ThermalJpeg.Index.APP1HEADER);
                    }
                }
            }
        } catch (IOException e) {
            throw e; 
        }
        if ( fff.size() == 0 ) {
            throw new FlirImageException("No thermal data present in file.");
        }
        return fromFFF((new ByteArrayInputStream(fff.toByteArray())));
    }
    
    public static FlirImage fromFFF(InputStream is) throws IOException, FlirImageException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        {
            byte[] b = new byte[1024 * 8];int len;
            while ((len = is.read(b)) > 0) { os.write(b, 0, len); }
            os.flush();
            is.close();
            os.close();
        }
        ByteBuffer buffer = ByteBuffer.wrap(os.toByteArray());
        // Check this is a valid file (because of the header)
        if (!FlirFormat.Header.FORMAT.equals(StandardCharsets.UTF_8.decode(buffer.slice(0, FlirFormat.Header.FORMAT.length())).toString())) {
            throw new FlirImageException("Content does not appear to be a valid FFF based on the header.");
        }
        
        FlirImage image = new FlirImage();
        // Creator (from header)
        image.creator = StandardCharsets.UTF_8.decode(buffer.slice(FlirFormat.Header.Index.CREATOR, 16)).toString().trim();
        // Root of the records
        int start = buffer.getInt(FlirFormat.Header.Index.RECORD_OFFSET);
        int count = buffer.getInt(FlirFormat.Header.Index.RECORD_NUMBER);

        int recordType, recordSub, recordOff, recordLen;
        ByteBuffer recordHeader,recordContent;
        for (int i=0; i<count; i++) {
            recordHeader = buffer.slice(start + (i * FlirFormat.FlirRecord.LENGTH), FlirFormat.FlirRecord.LENGTH);
            if ((recordType = recordHeader.getShort(0)) == FlirFormat.FlirRecord.Type.EMPTY) {
                continue; // Record is empty
            }
            recordSub = recordHeader.getShort(FlirFormat.FlirRecord.Index.SUB_TYPE);
            recordOff = recordHeader.getInt(FlirFormat.FlirRecord.Index.OFFSET);
            recordLen = recordHeader.getInt(FlirFormat.FlirRecord.Index.LENGTH);
            
            /**
             * Sections that have properties.
             */
            if (
                recordType == FlirFormat.FlirRecord.Type.PALETTE || 
                recordType == FlirFormat.FlirRecord.Type.CAMERA ||
                recordType == FlirFormat.FlirRecord.Type.PIP
                ) {
                recordContent = buffer.slice(recordOff, recordLen).order(ByteOrder.LITTLE_ENDIAN);
                List<FlirProperty<?>> properties;
                switch (recordType) {
                    case FlirFormat.FlirRecord.Type.PALETTE:
                        properties = FlirFormat.FlirRecord.Palette.allProperties();
                        break;
                    case FlirFormat.FlirRecord.Type.CAMERA:
                        properties = FlirFormat.FlirRecord.Camera.allProperties();
                        break;
                    case FlirFormat.FlirRecord.Type.PIP:
                        properties = FlirFormat.FlirRecord.Pip.allProperties();
                        break;
                    default:
                        properties = new ArrayList<>();
                }
                
                for (FlirProperty<?> property: properties) {
                    switch ( property.getType() ) {
                        case FlirProperty.Type.STR16:
                            property.setValue(StandardCharsets.UTF_8.decode(recordContent.slice(property.getIndex(), 16)).toString().trim());
                            break;
                        case FlirProperty.Type.STR32:
                            property.setValue(StandardCharsets.UTF_8.decode(recordContent.slice(property.getIndex(), 32)).toString().trim());
                            break;
                        case FlirProperty.Type.FLOAT:
                            property.setValue(recordContent.getFloat(property.getIndex()));
                            break;
                        case FlirProperty.Type.INT32:
                            property.setValue(recordContent.getInt(property.getIndex()));
                            break;
                        case FlirProperty.Type.INT16S:
                            property.setValue(recordContent.getShort(property.getIndex()));
                            break;
                        case FlirProperty.Type.INT16U:
                            property.setValue(Short.toUnsignedInt(recordContent.getShort(property.getIndex())));
                            break;
                        case FlirProperty.Type.BYTE1:
                            property.setValue(Integer.valueOf(recordContent.get(property.getIndex())));
                            break;
                        case FlirProperty.Type.COLOR:
                            property.setValue(new Integer[] {
                                recordContent.get(property.getIndex() + 0) & 0xff,
                                recordContent.get(property.getIndex() + 1) & 0xff, 
                                recordContent.get(property.getIndex() + 2) & 0xff
                            });
                            break;
                        default:
                    }
                }
                image.properties.addAll(properties);
                /*
                * Palette - the color data (of the built in palette)
                */
                if (recordType == FlirFormat.FlirRecord.Type.PALETTE ) {
                    image.paletteData = new int[recordContent.getInt(FlirFormat.FlirRecord.Palette.Index.COLORS)][];
                    for(int n = 0; n < image.paletteData.length; n++){
                        image.paletteData[n] = new int[] {
                            recordContent.get(FlirFormat.FlirRecord.Palette.Index.DATA + (n * 3) + 0) & 0xff,
                            recordContent.get(FlirFormat.FlirRecord.Palette.Index.DATA + (n * 3) + 1) & 0xff,
                            recordContent.get(FlirFormat.FlirRecord.Palette.Index.DATA + (n * 3) + 2) & 0xff};
                    }
                }
            }
            /*
             * RAW DATA
             */
            if (recordType == FlirFormat.FlirRecord.Type.RAW) {
                if (recordSub == FlirFormat.FlirRecord.Type.SubType.PNG) {
                    // Values are stored as a PNG
                    recordContent = buffer.slice(recordOff, recordLen).order(ByteOrder.BIG_ENDIAN);
                    int rawOff = FlirFormat.FlirRecord.Raw.Index.DATA;
                    int rawLen = (recordContent.capacity() - rawOff);
                    byte[] raw = new byte[rawLen];
                    recordContent.slice(rawOff, rawLen).get(raw);
                    BufferedImage png = ImageIO.read(new ByteArrayInputStream(raw));
                    image.imageWd = png.getWidth();
                    image.imageHt = png.getHeight();
                    //Image is of type BufferedImage.TYPE_USHORT_GRAY
                    short[] dat = ((DataBufferUShort) png.getRaster().getDataBuffer()).getData();
                    image.rawValues = new short[dat.length];
                    IntStream.range(0, dat.length).forEach(n -> image.rawValues[n] = Short.reverseBytes(dat[n]));
                } else {
                    // Little or Big Endian
                    recordContent = buffer.slice(recordOff, recordLen).order(recordSub == FlirFormat.FlirRecord.Type.SubType.LE?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
                    image.imageWd = recordContent.getShort(FlirFormat.FlirRecord.Raw.Index.WIDTH);
                    image.imageHt = recordContent.getShort(FlirFormat.FlirRecord.Raw.Index.HEIGHT);
                    //
                    int rawOff = FlirFormat.FlirRecord.Raw.Index.DATA;
                    int rawLen = (recordContent.capacity() - rawOff);
                    recordContent.slice(rawOff, rawLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(image.rawValues = new short[rawLen / 2]);
                }
            }
        }
        return image;
    }

    public static class FlirImageException extends Exception {

        FlirImageException(String message) {
            super(message);
        }

        FlirImageException(String message, Exception e) {
            super(message, e);
        }

    }

}
