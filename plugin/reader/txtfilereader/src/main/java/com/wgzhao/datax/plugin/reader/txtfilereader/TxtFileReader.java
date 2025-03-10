/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.datax.plugin.reader.txtfilereader;

import com.google.common.collect.Sets;
import com.wgzhao.datax.common.exception.DataXException;
import com.wgzhao.datax.common.plugin.RecordSender;
import com.wgzhao.datax.common.spi.Reader;
import com.wgzhao.datax.common.util.Configuration;
import com.wgzhao.datax.plugin.storage.reader.StorageReaderErrorCode;
import com.wgzhao.datax.plugin.storage.reader.StorageReaderUtil;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.wgzhao.datax.plugin.storage.reader.Constant.DEFAULT_ENCODING;
import static com.wgzhao.datax.plugin.storage.reader.Key.COLUMN;
import static com.wgzhao.datax.plugin.storage.reader.Key.COMPRESS;
import static com.wgzhao.datax.plugin.storage.reader.Key.ENCODING;
import static com.wgzhao.datax.plugin.storage.reader.Key.FIELD_DELIMITER;
import static com.wgzhao.datax.plugin.storage.reader.Key.INDEX;
import static com.wgzhao.datax.plugin.storage.reader.Key.TYPE;
import static com.wgzhao.datax.plugin.storage.reader.Key.VALUE;

/**
 * Created by haiwei.luo on 14-9-20.
 */
public class TxtFileReader
        extends Reader
{
    public static class Job
            extends Reader.Job
    {
        private static final Logger LOG = LoggerFactory.getLogger(Job.class);

        private Configuration originConfig = null;

        private List<String> path = null;

        private List<String> sourceFiles;

        private Map<String, Pattern> pattern;

        private Map<String, Boolean> isRegexPath;

        @Override
        public void init()
        {
            this.originConfig = this.getPluginJobConf();
            this.pattern = new HashMap<>();
            this.isRegexPath = new HashMap<>();
            this.validateParameter();
            StorageReaderUtil.validateParameter(this.originConfig);
        }

        private void validateParameter()
        {
            // Compatible with the old version, path is a string before
            String pathInString = this.originConfig.getNecessaryValue(Key.PATH,
                    TxtFileReaderErrorCode.REQUIRED_VALUE);
            if (StringUtils.isBlank(pathInString)) {
                throw DataXException.asDataXException(
                        TxtFileReaderErrorCode.REQUIRED_VALUE,
                        "您需要指定待读取的源目录或文件");
            }
            if (!pathInString.startsWith("[") && !pathInString.endsWith("]")) {
                path = new ArrayList<>();
                path.add(pathInString);
            }
            else {
                path = this.originConfig.getList(Key.PATH, String.class);
                if (null == path || path.isEmpty()) {
                    throw DataXException.asDataXException(
                            TxtFileReaderErrorCode.REQUIRED_VALUE,
                            "您需要指定待读取的源目录或文件");
                }
            }

            String encoding = this.originConfig
                    .getString(
                            ENCODING,
                            DEFAULT_ENCODING);
            if (StringUtils.isBlank(encoding)) {
                this.originConfig
                        .set(ENCODING,
                                DEFAULT_ENCODING);
            }
            else {
                try {
                    encoding = encoding.trim();
                    this.originConfig
                            .set(ENCODING,
                                    encoding);
                    Charsets.toCharset(encoding);
                }
                catch (UnsupportedCharsetException uce) {
                    throw DataXException.asDataXException(
                            TxtFileReaderErrorCode.ILLEGAL_VALUE,
                            String.format("不支持您配置的编码格式 : [%s]", encoding), uce);
                }
                catch (Exception e) {
                    throw DataXException.asDataXException(
                            TxtFileReaderErrorCode.CONFIG_INVALID_EXCEPTION,
                            String.format("编码配置异常, 请联系我们: %s", e.getMessage()),
                            e);
                }
            }

            // column: 1. index type 2.value type 3.when type is Date, may have
            // format
            List<Configuration> columns = this.originConfig
                    .getListConfiguration(COLUMN);
            // handle ["*"]
            if (null != columns && 1 == columns.size()) {
                String columnsInStr = columns.get(0).toString();
                if ("\"*\"".equals(columnsInStr) || "'*'".equals(columnsInStr)) {
                    this.originConfig
                            .set(COLUMN,
                                    null);
                    columns = null;
                }
            }

            if (null != columns && !columns.isEmpty()) {
                for (Configuration eachColumnConf : columns) {
                    eachColumnConf
                            .getNecessaryValue(
                                    TYPE,
                                    TxtFileReaderErrorCode.REQUIRED_VALUE);
                    Integer columnIndex = eachColumnConf
                            .getInt(INDEX);
                    String columnValue = eachColumnConf
                            .getString(VALUE);

                    if (null == columnIndex && null == columnValue) {
                        throw DataXException.asDataXException(
                                TxtFileReaderErrorCode.NO_INDEX_VALUE,
                                "由于您配置了type, 则至少需要配置 index 或 value");
                    }

                    if (null != columnIndex && null != columnValue) {
                        throw DataXException.asDataXException(
                                TxtFileReaderErrorCode.MIXED_INDEX_VALUE,
                                "您混合配置了index, value, 每一列同时仅能选择其中一种");
                    }
                    if (null != columnIndex && columnIndex < 0) {
                        throw DataXException.asDataXException(
                                TxtFileReaderErrorCode.ILLEGAL_VALUE, String
                                        .format("index需要大于等于0, 您配置的index为[%s]",
                                                columnIndex));
                    }
                }
            }

            // only support compress types
            String compress = this.originConfig
                    .getString(COMPRESS);
            if (StringUtils.isBlank(compress)) {
                this.originConfig
                        .set(COMPRESS,
                                null);
            }
            else {
                Set<String> supportedCompress = Sets
                        .newHashSet("gzip", "bzip2", "zip");
                compress = compress.toLowerCase().trim();
                if (!supportedCompress.contains(compress)) {
                    throw DataXException
                            .asDataXException(
                                    TxtFileReaderErrorCode.ILLEGAL_VALUE,
                                    String.format(
                                            "仅支持 gzip, bzip2, zip 文件压缩格式 , 不支持您配置的文件压缩格式: [%s]",
                                            compress));
                }
                this.originConfig
                        .set(COMPRESS,
                                compress);
            }

            String delimiterInStr = this.originConfig
                    .getString(FIELD_DELIMITER);
            // warn: if have, length must be one
            if (null != delimiterInStr && 1 != delimiterInStr.length()) {
                throw DataXException.asDataXException(
                        StorageReaderErrorCode.ILLEGAL_VALUE,
                        String.format("仅仅支持单字符切分, 您配置的切分为 : [%s]",
                                delimiterInStr));
            }
        }

        @Override
        public void prepare()
        {
            LOG.debug("prepare() begin...");
            // warn:make sure this regex string
            // warn:no need trim
            for (String eachPath : this.path) {
                String regexString = eachPath.replace("*", ".*").replace("?",
                        ".?");
                Pattern patt = Pattern.compile(regexString);
                this.pattern.put(eachPath, patt);
            }
            this.sourceFiles = this.buildSourceTargets();

            LOG.info(String.format("您即将读取的文件数为: [%s]", this.sourceFiles.size()));
        }

        @Override
        public void post()
        {
            //
        }

        @Override
        public void destroy()
        {
            //
        }

        // warn: 如果源目录为空会报错，拖空目录意图=>空文件显示指定此意图
        @Override
        public List<Configuration> split(int adviceNumber)
        {
            LOG.debug("split() begin...");
            List<Configuration> readerSplitConfigs = new ArrayList<>();

            // warn:每个slice拖且仅拖一个文件,
            // int splitNumber = adviceNumber
            int splitNumber = this.sourceFiles.size();
            if (0 == splitNumber) {
                throw DataXException.asDataXException(
                        TxtFileReaderErrorCode.EMPTY_DIR_EXCEPTION, String
                                .format("未能找到待读取的文件,请确认您的配置项path: %s",
                                        this.originConfig.getString(Key.PATH)));
            }

            List<List<String>> splitedSourceFiles = this.splitSourceFiles(
                    this.sourceFiles, splitNumber);
            for (List<String> files : splitedSourceFiles) {
                Configuration splitedConfig = this.originConfig.clone();
                splitedConfig.set(Constant.SOURCE_FILES, files);
                readerSplitConfigs.add(splitedConfig);
            }
            LOG.debug("split() ok and end...");
            return readerSplitConfigs;
        }

        // validate the path, path must be a absolute path
        private List<String> buildSourceTargets()
        {
            // for eath path
            Set<String> toBeReadFiles = new HashSet<>();
            for (String eachPath : this.path) {
                int endMark;
                for (endMark = 0; endMark < eachPath.length(); endMark++) {
                    if ('*' == eachPath.charAt(endMark)
                            || '?' == eachPath.charAt(endMark)) {
                        this.isRegexPath.put(eachPath, true);
                        break;
                    }
                }

                String parentDirectory;
                if (BooleanUtils.isTrue(this.isRegexPath.get(eachPath))) {
                    int lastDirSeparator = eachPath.substring(0, endMark)
                            .lastIndexOf(IOUtils.DIR_SEPARATOR);
                    parentDirectory = eachPath.substring(0,
                            lastDirSeparator + 1);
                }
                else {
                    this.isRegexPath.put(eachPath, false);
                    parentDirectory = eachPath;
                }
                this.buildSourceTargetsEathPath(eachPath, parentDirectory,
                        toBeReadFiles);
            }
            return Arrays.asList(toBeReadFiles.toArray(new String[0]));
        }

        private void buildSourceTargetsEathPath(String regexPath,
                String parentDirectory, Set<String> toBeReadFiles)
        {
            // 检测目录是否存在，错误情况更明确
            try {
                File dir = new File(parentDirectory);
                boolean isExists = dir.exists();
                if (!isExists) {
                    String message = String.format("您设定的目录不存在 : [%s]",
                            parentDirectory);
                    LOG.error(message);
                    throw DataXException.asDataXException(
                            TxtFileReaderErrorCode.FILE_NOT_EXISTS, message);
                }
            }
            catch (SecurityException se) {
                String message = String.format("您没有权限查看目录 : [%s]",
                        parentDirectory);
                LOG.error(message);
                throw DataXException.asDataXException(
                        TxtFileReaderErrorCode.SECURITY_NOT_ENOUGH, message);
            }

            directoryRover(regexPath, parentDirectory, toBeReadFiles);
        }

        private void directoryRover(String regexPath, String parentDirectory,
                Set<String> toBeReadFiles)
        {
            File directory = new File(parentDirectory);
            // is a normal file
            if (!directory.isDirectory()) {
                if (this.isTargetFile(regexPath, directory.getAbsolutePath())) {
                    toBeReadFiles.add(parentDirectory);
                    LOG.info("add file [{}] as a candidate to be read.",
                            parentDirectory);
                }
            }
            else {
                // 是目录
                try {
                    // warn:对于没有权限的目录,listFiles 返回null，而不是抛出SecurityException
                    File[] files = directory.listFiles();
                    if (null != files) {
                        for (File subFileNames : files) {
                            directoryRover(regexPath,
                                    subFileNames.getAbsolutePath(),
                                    toBeReadFiles);
                        }
                    }
                    else {
                        // warn: 对于没有权限的文件，是直接throw DataXException
                        String message = String.format("您没有权限查看目录 : [%s]",
                                directory);
                        LOG.error(message);
                        throw DataXException.asDataXException(
                                TxtFileReaderErrorCode.SECURITY_NOT_ENOUGH,
                                message);
                    }
                }
                catch (SecurityException e) {
                    String message = String.format("您没有权限查看目录 : [%s]",
                            directory);
                    LOG.error(message);
                    throw DataXException.asDataXException(
                            TxtFileReaderErrorCode.SECURITY_NOT_ENOUGH,
                            message, e);
                }
            }
        }

        // 正则过滤
        private boolean isTargetFile(String regexPath, String absoluteFilePath)
        {
            if (this.isRegexPath.get(regexPath)) {
                return this.pattern.get(regexPath).matcher(absoluteFilePath)
                        .matches();
            }
            else {
                return true;
            }
        }

        private <T> List<List<T>> splitSourceFiles(final List<T> sourceList,
                int adviceNumber)
        {
            List<List<T>> splitedList = new ArrayList<>();
            int averageLength = sourceList.size() / adviceNumber;
            averageLength = averageLength == 0 ? 1 : averageLength;

            for (int begin = 0, end; begin < sourceList.size(); begin = end) {
                end = begin + averageLength;
                if (end > sourceList.size()) {
                    end = sourceList.size();
                }
                splitedList.add(sourceList.subList(begin, end));
            }
            return splitedList;
        }
    }

    public static class Task
            extends Reader.Task
    {
        private static final Logger LOG = LoggerFactory.getLogger(Task.class);

        private Configuration readerSliceConfig;
        private List<String> sourceFiles;

        @Override
        public void init()
        {
            this.readerSliceConfig = this.getPluginJobConf();
            this.sourceFiles = this.readerSliceConfig.getList(
                    Constant.SOURCE_FILES, String.class);
        }

        @Override
        public void prepare()
        {
            //
        }

        @Override
        public void post()
        {
            //
        }

        @Override
        public void destroy()
        {
            //
        }

        @Override
        public void startRead(RecordSender recordSender)
        {
            LOG.debug("start read source files...");
            for (String fileName : this.sourceFiles) {
                LOG.info("reading file : [{}]", fileName);
                InputStream inputStream;
                try {
                    inputStream = new FileInputStream(fileName);
                    StorageReaderUtil.readFromStream(inputStream,
                            fileName, this.readerSliceConfig, recordSender,
                            this.getTaskPluginCollector());
                    recordSender.flush();
                }
                catch (FileNotFoundException e) {
                    // warn: sock 文件无法read,能影响所有文件的传输,需要用户自己保证
                    String message = String
                            .format("找不到待读取的文件 : [%s]", fileName);
                    LOG.error(message);
                    throw DataXException.asDataXException(
                            TxtFileReaderErrorCode.OPEN_FILE_ERROR, message);
                }
            }
            LOG.debug("end read source files...");
        }
    }
}
