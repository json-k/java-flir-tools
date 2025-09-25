package org.keeber.imaging.thermal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.keeber.imaging.thermal.FlirImage.FlirImageException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class Test {
    static final Logger logger = Logger.getGlobal();
    private static ObjectMapper JSON = JsonMapper.builder().configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true).enable(SerializationFeature.INDENT_OUTPUT).build();

    private static void print(Object o) {
        try {
            System.out.println( o instanceof String ? (String) o : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(o));
        } catch (JsonProcessingException e) {
            logger.log(Level.INFO, "Could not log:", o);
        }
    }

    public static void main(String[] args) {
        Arrays.stream(new File("./samples/").listFiles(file -> file.getName().endsWith(".jpg")))
                .forEach((File file) -> {
                    try (InputStream is = new FileInputStream(file)) {
                        print(file.getName());
                        FlirImage image = FlirImage.fromJPG(is);
                        JSON.writeValue(new FileOutputStream("build/" + file.getName() + ".json"), image);
                        // Default Image
                        ImageIO.write(image.getToolkit().asImageDefault(), "png", new FileOutputStream("build/" + file.getName() + "_DEFAULT.png"));
                        // Paletted Image(s)
                        int max = image.getToolkit().getStats().getMax();
                        int min = image.getToolkit().getStats().getMin();
                        ImageIO.write(image.getToolkit().asImagePalletted(FlirFormat.Palettes.WITEHOT, max, min,0x77ff0000,0x770000ff),"png", new FileOutputStream("build/" + file.getName() + "_LITEHOT.png"));
                        ImageIO.write(image.getToolkit().asImagePalletted(FlirFormat.Palettes.DARKHOT, max, min,0x77ff0000,0x770000ff),"png", new FileOutputStream("build/" + file.getName() + "_DARKHOT.png"));
                        ImageIO.write(image.getToolkit().asImagePalletted(FlirFormat.Palettes.FAKEBOW, max, min,0x77ff0000,0x770000ff),"png", new FileOutputStream("build/" + file.getName() + "_FAKEBOW.png"));
                        ImageIO.write(image.getToolkit().asImagePalletted(FlirFormat.Palettes.WIDEBOW, max, min,0x77ff0000,0x770000ff),"png", new FileOutputStream("build/" + file.getName() + "_WIDEBOW.png"));
                        try (BufferedWriter writer = new BufferedWriter(new FileWriter("build/" + file.getName() + ".svg"))) {
                            writer.write(image.getToolkit().asPrettySVG());
                        } catch (IOException e) {
                            throw e;
                        }
                        print(image);
                    } catch (FileNotFoundException e) {
                        logger.log(Level.SEVERE, file.getName(), e);
                    } catch (IOException e) {
                        logger.log(Level.SEVERE, file.getName(), e);
                    } catch (FlirImageException e) {
                        logger.log(Level.SEVERE, file.getName(), e);
                    }
                });
    }

}
