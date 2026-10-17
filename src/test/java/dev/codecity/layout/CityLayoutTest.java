package dev.codecity.layout;

import dev.codecity.analysis.MethodInfo;
import dev.codecity.analysis.TypeInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityLayoutTest {

    private static TypeInfo type(String pkg, String name, int noa) {
        return new TypeInfo(pkg + "." + name, name, pkg, "", "class", Path.of(name + ".java"), 1, 10, 10, noa, false,
                List.of(new MethodInfo("m", 2, 3, 1, 0)), Set.of(), List.of(), List.of());
    }

    private static List<TypeInfo> randomCity(long seed) {
        Random rnd = new Random(seed);
        String[] pkgs = {"a", "a.b", "a.b.c", "a.d", "e", "e.f.g"};
        List<TypeInfo> types = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            types.add(type(pkgs[rnd.nextInt(pkgs.length)], "T" + i, rnd.nextInt(5) == 0 ? rnd.nextInt(80) : rnd.nextInt(6)));
        }
        return types;
    }

    @Test
    void everyTypeGetsALot() {
        List<TypeInfo> types = randomCity(1);
        CityLayout.Result r = CityLayout.layout("city", types);
        assertEquals(types.size(), r.lots().size());
    }

    @Test
    void buildingsInTheSameDistrictDoNotOverlap() {
        CityLayout.Result r = CityLayout.layout("city", randomCity(2));
        List<CityLayout.Lot> lots = new ArrayList<>(r.lots().values());
        for (int i = 0; i < lots.size(); i++) {
            for (int j = i + 1; j < lots.size(); j++) {
                CityLayout.Lot a = lots.get(i);
                CityLayout.Lot b = lots.get(j);
                double gapX = Math.abs(a.x() - b.x()) - (a.side() + b.side()) / 2;
                double gapZ = Math.abs(a.z() - b.z()) - (a.side() + b.side()) / 2;
                assertTrue(gapX >= CityLayout.GAP - 1e-6 || gapZ >= CityLayout.GAP - 1e-6,
                        "lots " + a + " and " + b + " overlap");
            }
        }
    }

    @Test
    void buildingsStandInsideTheirDistrict() {
        CityLayout.Result r = CityLayout.layout("city", randomCity(3));
        for (CityLayout.Lot lot : r.lots().values()) {
            CityLayout.District d = r.districts().get(lot.districtIndex());
            assertTrue(lot.x() - lot.side() / 2 >= d.x() - d.w() / 2 - 1e-6);
            assertTrue(lot.x() + lot.side() / 2 <= d.x() + d.w() / 2 + 1e-6);
            assertTrue(lot.z() - lot.side() / 2 >= d.z() - d.d() / 2 - 1e-6);
            assertTrue(lot.z() + lot.side() / 2 <= d.z() + d.d() / 2 + 1e-6);
            assertEquals((d.depth() + 1) * CityLayout.PLATFORM, lot.y(), 1e-9);
        }
    }

    @Test
    void singleChildPackageChainsAreCollapsed() {
        CityLayout.Result r = CityLayout.layout("city", List.of(type("com.acme.shop", "Cart", 2)));
        List<String> labels = r.districts().stream().map(CityLayout.District::label).toList();
        assertEquals(List.of("city", "com.acme.shop"), labels);
    }

    @Test
    void cityIsRoughlySquare() {
        List<TypeInfo> types = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            types.add(type("p", "T" + i, i % 7));
        }
        Collections.shuffle(types, new Random(4));
        CityLayout.Result r = CityLayout.layout("city", types);
        double aspect = r.width() / r.depth();
        assertFalse(aspect > 2 || aspect < 0.5, "aspect " + aspect);
    }
}
