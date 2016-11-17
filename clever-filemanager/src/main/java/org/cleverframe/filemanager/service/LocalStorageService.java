package org.cleverframe.filemanager.service;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.cleverframe.common.configuration.FilemanagerConfigNames;
import org.cleverframe.common.configuration.FilemanagerConfigValues;
import org.cleverframe.common.configuration.IConfig;
import org.cleverframe.common.service.BaseService;
import org.cleverframe.common.spring.SpringBeanNames;
import org.cleverframe.common.spring.SpringContextHolder;
import org.cleverframe.common.utils.IDCreateUtils;
import org.cleverframe.filemanager.FilemanagerBeanNames;
import org.cleverframe.filemanager.dao.FileInfoDao;
import org.cleverframe.filemanager.entity.FileInfo;
import org.cleverframe.filemanager.utils.FileDigestUtils;
import org.cleverframe.filemanager.utils.StoragePathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * 上传文件存储到当前服务器的Service<br>
 * <p>
 * 作者：LiZW <br/>
 * 创建时间：2016/11/17 22:17 <br/>
 */
@Service(FilemanagerBeanNames.LocalStorageService)
public class LocalStorageService extends BaseService implements IStorageService {
    private final static Logger logger = LoggerFactory.getLogger(LocalStorageService.class);

    /**
     * 上传文件存储到当前服务器的路径，如：F:\fileStoragePath<br>
     * <p>
     * <b>注意：路径后面没有多余的“\”或者“/”</b>
     */
    public final static String FILE_STORAGE_PATH;

    // 初始化 存储路径
    static {
        IConfig config = SpringContextHolder.getBean(SpringBeanNames.Config);
        if (config == null) {
            throw new RuntimeException("Bean[" + SpringBeanNames.Config + "]获取失败");
        }
        String path = config.getConfig(FilemanagerConfigNames.FILE_STORAGE_PATH);
        if (StringUtils.isBlank(path)) {
            path = FilemanagerConfigValues.FILE_STORAGE_PATH;
        }
        FILE_STORAGE_PATH = FilenameUtils.normalizeNoEndSeparator(path);
        logger.info("### 上传文件存储到当前服务器的路径地址[{}]", FILE_STORAGE_PATH);
    }

    @Autowired
    @Qualifier(FilemanagerBeanNames.FileInfoDao)
    private FileInfoDao fileInfoDao;

    @Override
    public FileInfo lazySaveFile(String fileName, String fileDigest, Character digestType) throws Exception {
        FileInfo dbFileInfo;
        if (StringUtils.isBlank(fileDigest) || digestType == null) {
            return null;
        }
        // 到数据库查找判断此文件是否已经上传过了 - 此文件是否已经上传过了，不需要重复保存
        dbFileInfo = fileInfoDao.findFileInfoByDigest(fileDigest, digestType);
        if (dbFileInfo == null || StringUtils.isBlank(dbFileInfo.getFilePath()) || StringUtils.isBlank(dbFileInfo.getNewName())) {
            return null;
        } else {
            String filepath = FILE_STORAGE_PATH + dbFileInfo.getFilePath() + File.separator + dbFileInfo.getNewName();
            File file = new File(filepath);
            if (!file.exists() || !file.isFile()) {
                return null;
            }
        }
        // 已找到上传文件，保存文件信息
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setUploadTime(0L);
        fileInfo.setStoredTime(0L);
        // 复制信息
        fileInfo.setStoredType(dbFileInfo.getStoredType());
        fileInfo.setFilePath(dbFileInfo.getFilePath());
        fileInfo.setDigest(dbFileInfo.getDigest());
        fileInfo.setDigestType(dbFileInfo.getDigestType());
        fileInfo.setFileSize(dbFileInfo.getFileSize());
        fileInfo.setNewName(dbFileInfo.getNewName());
        // 保存文件信息
        fileInfoDao.getHibernateDao().save(fileInfo);
        return fileInfo;
    }

    @Override
    public FileInfo saveFile(long uploadTime, MultipartFile multipartFile) throws Exception {
        // 设置文件签名类型 和 文件签名
        String digest;
        try (InputStream inputStream = multipartFile.getInputStream()) {
            digest = FileDigestUtils.FileDigestByMD5(inputStream);
        }
        // 通过文件签名检查服务器端是否有相同文件
        FileInfo lazyFileInfo = this.lazySaveFile(multipartFile.getOriginalFilename(), digest, FileInfo.MD5_DIGEST);
        if (lazyFileInfo != null) {
            return lazyFileInfo;
        }
        // 服务器端不存在相同文件
        FileInfo fileInfo = new FileInfo();
        fileInfo.setUploadTime(uploadTime);
        fileInfo.setFileName(multipartFile.getOriginalFilename());
        fileInfo.setFileSize(multipartFile.getSize());
        fileInfo.setDigest(digest);
        fileInfo.setDigestType(FileInfo.MD5_DIGEST);
        // 上传文件的存储类型：当前服务器硬盘
        fileInfo.setStoredType(FileInfo.LOCAL_STORAGE);
        // 设置文件存储之后的名称：UUID + 后缀名(此操作依赖文件原名称)
        String newName = IDCreateUtils.uuid() + "." + FilenameUtils.getExtension(fileInfo.getFileName());
        fileInfo.setNewName(newName);
        // 上传文件存储到当前服务器的路径(相对路径，相对于 FILE_STORAGE_PATH)
        String filePath = StoragePathUtils.createFilePathByDate("");
        fileInfo.setFilePath(filePath);
        // 计算文件的绝对路径，保存文件
        String absoluteFilePath = FILE_STORAGE_PATH + filePath + File.separator + newName;
        File file = new File(absoluteFilePath);
        long start = System.currentTimeMillis();
        // 文件夹不存在，创建文件夹
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            if (parentFile.mkdirs()) {
                logger.info("创建文件夹：" + parentFile.getPath());
            } else {
                throw new RuntimeException("创建文件夹[" + parentFile.getPath() + "]失败");
            }
        }
        // 如果filePath表示的不是一个路径，文件就会被存到System.getProperty("user.dir")路径下
        multipartFile.transferTo(file);
        long end = System.currentTimeMillis();
        // 设置存储所用的时间
        fileInfo.setStoredTime(end - start);
        // 保存文件信息
        fileInfoDao.getHibernateDao().save(fileInfo);
        return fileInfo;
    }

    @Override
    public int deleteFile(Serializable fileInfoUuid, boolean lazy) throws Exception {
        // 1：成功删除fileInfo和服务器端文件；2：只删除了fileInfo；3：fileInfo不存在
        FileInfo fileInfo = fileInfoDao.getFileInfoByUuid(fileInfoUuid);
        if (fileInfo == null || fileInfo.getDelFlag() == FileInfo.DEL_FLAG_DELETE) {
            // FileInfo 不存在或已经被删除
            return 3;
        }
        // TODO 删除FileInfo要确保系统没有引用或使用该FileInfo实例(确保该文件没有被使用)
        fileInfoDao.getHibernateDao().delete(fileInfo);
        if (lazy) {
            // lazy == true:只删除FileInfo
            return 2;
        }
        int count = fileInfoDao.findRepeatFile(fileInfo.getFilePath(), fileInfo.getNewName());
        if (count > 1) {
            // 此文件被多个FileInfo引用
            return 2;
        }
        String fullPath = FILE_STORAGE_PATH + fileInfo.getFilePath();
        fullPath = FilenameUtils.concat(fullPath, fileInfo.getNewName());
        File file = new File(fullPath);
        if (file.exists() && file.isFile()) {
            if (!file.delete()) {
                throw new Exception("文件删除失败：" + fullPath);
            }
        }
        return 1;
    }

    @Override
    public FileInfo isExists(Serializable fileInfoUuid) throws Exception {
        FileInfo fileInfo = fileInfoDao.getFileInfoByUuid(fileInfoUuid);
        if (fileInfo == null || fileInfo.getDelFlag() == FileInfo.DEL_FLAG_DELETE) {
            return null;
        }
        String fullPath = FILE_STORAGE_PATH + fileInfo.getFilePath();
        fullPath = FilenameUtils.concat(fullPath, fileInfo.getNewName());
        File file = new File(fullPath);
        if (file.exists() && file.isFile()) {
            return fileInfo;
        }
        return null;
    }

    @Override
    public FileInfo openFile(Serializable fileInfoUuid, OutputStream outputStream) throws Exception {
        FileInfo fileInfo = fileInfoDao.getFileInfoByUuid(fileInfoUuid);
        if (fileInfo != null && fileInfo.getDelFlag() != FileInfo.DEL_FLAG_DELETE) {
            String fullPath = FILE_STORAGE_PATH + fileInfo.getFilePath();
            fullPath = FilenameUtils.concat(fullPath, fileInfo.getNewName());
            File file = new File(fullPath);
            if (file.exists() && file.isFile()) {
                try (InputStream inputStream = FileUtils.openInputStream(file)) {
                    byte[] data = new byte[256 * 1024];
                    while (inputStream.read(data) > -1) {
                        outputStream.write(data);
                    }
                }
                return fileInfo;
            }
        }
        return null;
    }
}
