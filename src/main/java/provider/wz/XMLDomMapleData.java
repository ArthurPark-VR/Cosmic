/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package provider.wz;

import constants.game.GameConstants;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import provider.Data;
import provider.DataEntity;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class XMLDomMapleData implements Data {
    private final Node node;

    /*
     * One lock per parsed document, shared by every wrapper over any node inside it.
     *
     * These methods were already `synchronized`, and that lock had never excluded anything. Walking
     * the tree returns a NEW XMLDomMapleData around each node reached, so two threads reading the
     * same document hold monitors on two different wrappers and proceed straight into the shared
     * DOM together.
     *
     * That matters because a DOM read is not read-only. Xerces builds child-list state lazily on
     * first access (ParentNode.fNodeListCache), so concurrent readers of one node race to build and
     * invalidate it, and the loser dereferences a cache another thread has just nulled:
     *
     *   NullPointerException: Cannot read field "fChild" because "this.fNodeListCache" is null
     *       at ...ParentNode.nodeListGetLength
     *       at XMLDomMapleData.getChildByPath
     *
     * Every provider in the server is this class (DataProviderFactory hands out XMLWZFile), and
     * long-lived roots like ItemInformationProvider's Eqp.img are read from every bot tick thread,
     * so "one shared document, many readers" is the normal case rather than an unusual one.
     *
     * Threading a lock through descendants, rather than locking on the node or the Document, keeps
     * it a plain final field: it is fixed at parse time and needs no DOM call of its own to find -
     * which would itself be a DOM read outside the lock.
     */
    private final Object domLock;

    private Path imageDataDir;

    public XMLDomMapleData(FileInputStream fis, Path imageDataDir) {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(fis);
            this.node = document.getFirstChild();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.domLock = new Object();
        this.imageDataDir = imageDataDir;
    }

    private XMLDomMapleData(Node node, Object domLock) {
        this.node = node;
        this.domLock = domLock;
    }

    @Override
    public Data getChildByPath(String path) {
        synchronized (domLock) {
            // The original note here read "the whole XML reading system seems susceptible to give
            // nulls on strenuous read scenarios". That was this race, seen from the outside; the
            // lock above is the thing the `synchronized` keyword was reaching for.
            String[] segments = path.split("/");
            if (segments[0].equals("..")) {
                return ((Data) getParent()).getChildByPath(path.substring(path.indexOf("/") + 1));
            }

            Node myNode;
            myNode = node;
            for (String s : segments) {
                NodeList childNodes = myNode.getChildNodes();
                boolean foundChild = false;
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node childNode = childNodes.item(i);
                    if (childNode.getNodeType() == Node.ELEMENT_NODE
                            && childNode.getAttributes().getNamedItem("name").getNodeValue().equals(s)) {
                        myNode = childNode;
                        foundChild = true;
                        break;
                    }
                }
                if (!foundChild) {
                    return null;
                }
            }

            XMLDomMapleData ret = new XMLDomMapleData(myNode, domLock);
            ret.imageDataDir = imageDataDir.resolve(getName().trim()).resolve(path).getParent();
            return ret;
        }
    }

    @Override
    public List<Data> getChildren() {
        synchronized (domLock) {
            List<Data> ret = new ArrayList<>();

            NodeList childNodes = node.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node childNode = childNodes.item(i);
                if (childNode.getNodeType() == Node.ELEMENT_NODE) {
                    XMLDomMapleData child = new XMLDomMapleData(childNode, domLock);
                    child.imageDataDir = imageDataDir.resolve(getName().trim());
                    ret.add(child);
                }
            }

            return ret;
        }
    }

    @Override
    public Object getData() {
        synchronized (domLock) {
            NamedNodeMap attributes = node.getAttributes();
            DataType type = getType();
            switch (type) {
                case DOUBLE:
                case FLOAT:
                case INT:
                case SHORT: {
                    String value = attributes.getNamedItem("value").getNodeValue();
                    Number nval = GameConstants.parseNumber(value);

                    switch (type) {
                        case DOUBLE:
                            return nval.doubleValue();
                        case FLOAT:
                            return nval.floatValue();
                        case INT:
                            return nval.intValue();
                        case SHORT:
                            return nval.shortValue();
                        default:
                            return null;
                    }
                }
                case STRING:
                case UOL: {
                    String value = attributes.getNamedItem("value").getNodeValue();
                    return value;
                }
                case VECTOR: {
                    String x = attributes.getNamedItem("x").getNodeValue();
                    String y = attributes.getNamedItem("y").getNodeValue();
                    return new Point(Integer.parseInt(x), Integer.parseInt(y));
                }
                default:
                    return null;
            }
        }
    }

    @Override
    public DataType getType() {
        synchronized (domLock) {
            String nodeName = node.getNodeName();

            switch (nodeName) {
                case "imgdir":
                    return DataType.PROPERTY;
                case "canvas":
                    return DataType.CANVAS;
                case "convex":
                    return DataType.CONVEX;
                case "sound":
                    return DataType.SOUND;
                case "uol":
                    return DataType.UOL;
                case "double":
                    return DataType.DOUBLE;
                case "float":
                    return DataType.FLOAT;
                case "int":
                    return DataType.INT;
                case "short":
                    return DataType.SHORT;
                case "string":
                    return DataType.STRING;
                case "vector":
                    return DataType.VECTOR;
                case "null":
                    return DataType.IMG_0x00;
            }
            return null;
        }
    }

    @Override
    public DataEntity getParent() {
        synchronized (domLock) {
            Node parentNode;
            parentNode = node.getParentNode();
            if (parentNode.getNodeType() == Node.DOCUMENT_NODE) {
                return null;
            }
            XMLDomMapleData parentData = new XMLDomMapleData(parentNode, domLock);
            parentData.imageDataDir = imageDataDir.getParent();
            return parentData;
        }
    }

    @Override
    public String getName() {
        synchronized (domLock) {
            return node.getAttributes().getNamedItem("name").getNodeValue();
        }
    }

    @Override
    public Iterator<Data> iterator() {
        synchronized (domLock) {
            return getChildren().iterator();
        }
    }
}
