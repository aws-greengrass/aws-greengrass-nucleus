# importing required modules 
from zipfile import ZipFile 
import os 
import argparse

parser = argparse.ArgumentParser(description='Process some integers.')
parser.add_argument('--package', dest='package', required=True)
parser.add_argument('--version', dest='version', required=True)
parser.add_argument('--output', dest='output', required=True)

args = parser.parse_args()
print("parsing package:", args.package, flush=True)

def get_all_file_paths(directory):

    # initializing empty file paths list 
    file_paths = []

    # crawling through directory and subdirectories 
    for root, directories, files in os.walk(directory):
        for filename in files:
            # join the two strings in order to form the full filepath. 
            filepath = os.path.join(root, filename)
            file_paths.append(filepath)

    # returning all file paths
    return file_paths

def main():
    # path to folder which needs to be zipped 
    directory = os.path.dirname(os.path.realpath(__file__)) + '/packages/artifacts/' + args.package + '/' + args.version
    print("zipping package at " + directory, flush=True)
    # calling function to get all file paths in the directory
    file_paths = get_all_file_paths(directory)

    # writing files to a zipfile
    with ZipFile(args.output + "/" + args.package + '-' + args.version + '.zip','w') as zip:
        # writing each file one by one
        for file in file_paths:
            zip.write(file, os.path.relpath(file, directory))

    print('All files zipped successfully!')

if __name__ == "__main__":
    main()